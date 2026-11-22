package dev.sharedledger.sync

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.sharedledger.data.*
import dev.sharedledger.domain.BudgetType
import dev.sharedledger.domain.Role
import dev.sharedledger.domain.id
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

data class SyncStatus(val running: Boolean = false, val text: String = "Ready to sync", val lastSync: Long? = null)

@Serializable
private data class ManifestItem(val id: String, val seq: Long, val role: Role)

@Serializable
private data class RemoteRecord(val kind: String, val version: Long, val body: JsonObject, val operation: String? = null)

@Serializable
private data class Snapshot(val seq: Long, val records: List<RemoteRecord>, val members: List<Member>)

@Serializable
data class Invitation(val id: String, val role: Role, val expires: String, val used: Int, val revoked: Boolean)

@Singleton
class SyncEngine internal constructor(
    private val api: CloudApi, private val auth: CloudAuth, private val repo: LedgerRepository,
    private val requestSync: () -> Unit
) {
    @Inject constructor(api: CloudApi, auth: CloudAuth, repo: LedgerRepository, @ApplicationContext context: Context) :
        this(api, auth, repo, { Background.enqueueSync(context) })

    private val lock = Mutex()
    private val dao get() = repo.dao
    val status = MutableStateFlow(SyncStatus())
    val configured get() = api.configured
    private val invitationService = InvitationService(auth, api::rpc) { Log.i("SharedLedgerInvite", it) }

    suspend fun synchronize(retryFailed: Boolean = false): Boolean = lock.withLock {
        if (!configured) return true
        status.value = status.value.copy(running = true, text = "Checking for changes…")
        try {
            val session = auth.current()
            if (session == null) {
                status.value = SyncStatus(text = "Sign in to sync")
                return true
            }
            val user = session.userId
            status.value = status.value.copy(lastSync = dao.lastSync(user))
            var warning = pull(session)
            if (retryFailed) retryChanges(user)
            pushPending(session)
            warning = pull(session) ?: warning
            if (repo.principal.value != user) {
                status.value = SyncStatus(text = "Sign in to sync")
                return true
            }
            val remaining = scopedPending(user)
            val text = when {
                warning != null -> warning
                remaining.any { it.failure != null && it.deletesEntry() } -> "A transaction deletion needs attention."
                remaining.any { it.failure != null } -> "Some changes need attention."
                remaining.any { it.remoteVersion != null } -> "Resolve conflicting changes to finish syncing."
                remaining.isNotEmpty() -> "Changes are waiting for related records to sync."
                else -> "Up to date"
            }
            val lastSync = if (remaining.isEmpty() && warning == null) {
                System.currentTimeMillis().also { dao.put(SyncCheckpoint(user, it)) }
            } else dao.lastSync(user)
            status.value = SyncStatus(text = text, lastSync = lastSync)
            true
        } catch (e: CancellationException) {
            status.value = status.value.copy(running = false)
            throw e
        } catch (e: CloudError) {
            var retry = e.isTransient
            if (e.status == 401) {
                try {
                    retry = auth.current(forceRefresh = true) != null
                } catch (refresh: CloudError) {
                    retry = refresh.isTransient && refresh.status != 401
                }
            }
            status.value = status.value.copy(running = false, text = e.syncMessage())
            retry.not()
        } catch (e: Exception) {
            Log.w("SharedLedgerSync", "Sync interrupted category=${e.javaClass.simpleName}")
            status.value = status.value.copy(running = false, text = "Sync interrupted. Local changes are queued.")
            false
        }
    }

    private suspend fun scopedPending(user: String): List<PendingChange> =
        dao.pending().filter { (it.principal ?: dao.budget(it.budgetId)?.principal) == user }

    private suspend fun retryChanges(user: String) = repo.db.withTransaction {
        if (repo.principal.value != user) return@withTransaction
        for (change in scopedPending(user).filter { it.failure != null }) {
            val budget = dao.budget(change.budgetId) ?: continue
            if (budget.principal == user && budget.role != Role.VIEWER) {
                if (change.failure == SyncFailure.CONFLICT.name && change.deletesEntry()) {
                    dao.put(change.copy(baseVersion = change.remoteVersion ?: change.baseVersion,
                        remoteVersion = null, remoteBody = null, failure = null))
                } else dao.put(change.copy(failure = null))
            }
        }
    }

    private suspend fun pushPending(session: Session) {
        val queue = scopedPending(session.userId)
        val outstanding = queue.associateBy { it.key }.toMutableMap()
        val splitImports = hashSetOf<String>()
        var index = 0
        while (index < queue.size && repo.principal.value == session.userId) {
            val change = queue[index]
            val budget = dao.budget(change.budgetId)
            if (budget?.principal != session.userId || outstanding[change.key] == null ||
                change.failure != null || change.remoteVersion != null && !change.deletesEntry()) {
                index++
                continue
            }
            val body = change.payload()
            if (body == null) {
                rejectChange(session, change, SyncFailure.INVALID)
                index++
                continue
            }
            if (change.dependencies(body).any { it in outstanding }) {
                index++
                continue
            }

            val batch = mutableListOf(change)
            if (change.isImportCreation() && change.budgetId !in splitImports) {
                val available = hashSetOf(change.key)
                for (next in queue.subList(index + 1, minOf(index + 50, queue.size))) {
                    if (next.budgetId != change.budgetId || !next.isImportCreation() ||
                        next.failure != null || next.remoteVersion != null) break
                    val payload = next.payload() ?: break
                    if (next.dependencies(payload).any { it in outstanding && it !in available }) break
                    batch.add(next)
                    available.add(next.key)
                }
            }

            val count = try {
                if (change.isImportCreation()) pushImport(session, batch)
                else {
                    val value = api.rpc("ledger_apply", session.access, buildJsonObject {
                        put("p_kind", change.kind); put("p_budget", change.budgetId); put("p_id", change.recordId)
                        put("p_base", change.baseVersion); put("p_operation", change.operationId)
                        put("p_body", change.pushPayload(body))
                    }).jsonObject
                    applyPush(session, change, value)
                    1
                }
            } catch (e: CloudError) {
                if (e.isTransient) throw e
                if (batch.size > 1) {
                    // The server rolls back a rejected batch. Replay one by one to identify the failure.
                    splitImports.add(change.budgetId)
                    continue
                }
                rejectChange(session, change, SyncFailure.from(e), e)
                1
            }
            for (sent in batch.take(count)) {
                val current = dao.pending(sent.kind, sent.recordId)
                if (current == null) outstanding.remove(sent.key) else outstanding[sent.key] = current
            }
            index += count
        }
    }

    private suspend fun rejectChange(session: Session, sent: PendingChange, failure: SyncFailure, error: CloudError? = null) {
        Log.w("SharedLedgerSync", "kind=${sent.kind} delete=${sent.deletesEntry()} failure=$failure " +
            "sqlstate=${error?.code.orEmpty()} constraint=${error?.constraintName().orEmpty()}")
        repo.db.withTransaction {
            if (repo.principal.value != session.userId) return@withTransaction
            val current = dao.pending(sent.kind, sent.recordId)
            if (current?.operationId == sent.operationId) {
                dao.put(current.copy(failure = failure.name, principal = session.userId))
            }
        }
    }

    suspend fun verifySharedCreation(userId: String) {
        require(configured) { "Configure cloud sync before creating a shared budget." }
        val session = requireNotNull(auth.current(expectedUserId = userId)) { "Sign in before creating a shared budget." }
        api.rpc("ledger_manifest", session.access)
    }

    private fun PendingChange.isImportCreation() = imported && baseVersion == 0L && kind != "budget" && !deletesEntry()

    private suspend fun pushImport(session: Session, batch: List<PendingChange>): Int {
        val result = api.rpc("ledger_import_batch", session.access, buildJsonObject {
            put("p_budget", batch.first().budgetId)
            put("p_changes", buildJsonArray {
                for (change in batch) add(buildJsonObject {
                    put("kind", change.kind)
                    put("id", change.recordId)
                    put("operation", change.operationId)
                    put("body", ledgerJson.parseToJsonElement(change.body))
                })
            })
        }).jsonArray
        check(result.isNotEmpty() && result.size <= batch.size)
        val replies = result.map { it.jsonObject }
        check(replies.dropLast(1).all { it.string("status") == "ok" } &&
            (replies.last().string("status") == "conflict" || replies.size == batch.size && replies.last().string("status") == "ok"))
        repo.db.withTransaction {
            replies.forEachIndexed { i, reply -> applyPush(session, batch[i], reply) }
        }
        return replies.size
    }

    private suspend fun applyPush(session: Session, sent: PendingChange, result: JsonObject) = repo.db.withTransaction {
        if (repo.principal.value != session.userId) return@withTransaction
        val current = dao.pending(sent.kind, sent.recordId) ?: return@withTransaction
        val version = result["version"]?.jsonPrimitive?.long ?: error("Missing version")
        val remote = result["body"] as? JsonObject
        val record = remote?.let { RemoteRecord(sent.kind, version, it) }
        val budget = dao.budget(sent.budgetId) ?: return@withTransaction
        val response = result.string("status")
        check(response == "ok" || response == "conflict")
        if (record?.isEntryTombstone() == true) {
            saveRemote(session, budget.role, record)
            dao.removePending(sent.kind, sent.recordId)
        } else if (response == "ok") {
            dao.put(RemoteVersion(sent.kind, sent.recordId, sent.budgetId, version))
            if (current.operationId == sent.operationId) {
                if (record != null) saveRemote(session, budget.role, record)
                dao.removePending(sent.kind, sent.recordId)
            } else dao.put(current.copy(baseVersion = version, remoteVersion = null, remoteBody = null))
        } else if (record != null && !current.deletesEntry() && (current.rateOnly || current.kind == "entry" && current.baseVersion == 0L &&
            record.body.string("ruleId") != null && record.body.string("deletedAt") == null && current.operationId == sent.operationId)) {
            saveRemote(session, budget.role, record)
            dao.removePending(sent.kind, sent.recordId)
        } else {
            val failedDelete = sent.deletesEntry() && current.operationId == sent.operationId
            dao.put(current.copy(remoteVersion = version, remoteBody = remote?.toString(),
                failure = if (failedDelete) SyncFailure.CONFLICT.name else current.failure))
        }
    }

    private fun RemoteRecord.isEntryTombstone() = kind == "entry" && body.string("deletedAt") != null

    private suspend fun pull(session: Session): String? {
        val manifest: List<ManifestItem> = ledgerJson.decodeFromJsonElement(api.rpc("ledger_manifest", session.access))
        val accessible = manifest.mapTo(hashSetOf()) { it.id }
        var warning: String? = null
        repo.db.withTransaction {
            if (repo.principal.value != session.userId) return@withTransaction
            val changes = dao.pending().groupBy { it.budgetId }
            for (budget in dao.protectedBudgets(session.userId)) {
                if (budget.id in accessible) continue
                val pending = changes[budget.id].orEmpty()
                val own = pending.filter { it.principal == null || it.principal == session.userId }
                val creating = own.any { it.kind == "budget" && it.baseVersion == 0L }
                if (creating) continue
                if (own.isNotEmpty()) {
                    warning = "A budget is unavailable. Its unsynced changes are saved on this device."
                    own.forEach { dao.put(it.copy(principal = session.userId, failure = SyncFailure.PERMISSION.name)) }
                }
                repo.purgeBudget(budget.id, retainPending = pending.isNotEmpty())
            }
        }
        for (key in manifest) {
            if (repo.principal.value != session.userId) break
            val cached = dao.budget(key.id)
            if (cached?.principal == session.userId && cached.role == key.role && dao.version("snapshot", key.id) == key.seq) continue
            val value = api.rpc("ledger_snapshot", session.access, buildJsonObject { put("p_budget", key.id) })
            if (value == JsonNull) continue
            val snapshot: Snapshot = ledgerJson.decodeFromJsonElement(value)
            repo.db.withTransaction {
                if (repo.principal.value != session.userId) return@withTransaction
                val previous = dao.budget(key.id)
                if (previous?.principal != null && previous.principal != session.userId && dao.pending().any { it.budgetId == key.id }) {
                    warning = "Sign in as the previous account to synchronize this budget's queued changes."
                    return@withTransaction
                }
                val role = snapshot.members.find { it.userId == session.userId && it.active }?.role ?: return@withTransaction
                for (remote in snapshot.records) {
                    val recordId = remote.body.string("id") ?: error("Missing id")
                    val local = dao.pending(remote.kind, recordId)?.takeIf { it.principal == null || it.principal == session.userId }
                    if (local == null) saveRemote(session, role, remote)
                    else if (remote.isEntryTombstone() || local.operationId == remote.operation) {
                        saveRemote(session, role, remote)
                        dao.removePending(local.kind, local.recordId)
                    } else if (local.deletesEntry()) {
                        // A pending local deletion keeps the entry hidden over an active snapshot.
                        continue
                    } else if (remote.kind == "budget" && previous == null) {
                        saveRemote(session, role, remote)
                    } else if (local.rateOnly && (local.baseVersion != remote.version || role == Role.VIEWER)) {
                        saveRemote(session, role, remote)
                        dao.removePending(local.kind, local.recordId)
                    } else if (local.baseVersion != remote.version) {
                        if (remote.kind == "entry" && local.baseVersion == 0L && remote.body.string("ruleId") != null &&
                            remote.body.string("deletedAt") == null) {
                            saveRemote(session, role, remote)
                            dao.removePending(remote.kind, recordId)
                        } else {
                            dao.put(local.copy(remoteVersion = remote.version, remoteBody = remote.body.toString(), failure = null))
                        }
                    }
                }
                val localBudget = dao.budget(key.id) ?: return@withTransaction
                if (localBudget.role != role) dao.put(localBudget.copy(role = role))
                dao.removeMembers(key.id)
                snapshot.members.forEach { dao.put(it) }
                dao.put(RemoteVersion("snapshot", key.id, key.id, snapshot.seq))
            }
        }
        return warning
    }

    private suspend fun saveRemote(session: Session, role: Role, remote: RemoteRecord) {
        val recordId = remote.body.string("id") ?: error("Missing id")
        val budgetId = remote.body.string("budgetId") ?: recordId
        when (remote.kind) {
            "budget" -> dao.put(ledgerJson.decodeFromJsonElement<Budget>(remote.body).copy(principal = session.userId, role = role))
            "account" -> dao.put(ledgerJson.decodeFromJsonElement<Account>(remote.body))
            "category" -> dao.put(ledgerJson.decodeFromJsonElement<Category>(remote.body))
            "entry" -> dao.put(ledgerJson.decodeFromJsonElement<Entry>(remote.body))
            "rule" -> dao.put(ledgerJson.decodeFromJsonElement<Rule>(remote.body))
            else -> error("Unknown remote kind")
        }
        dao.put(RemoteVersion(remote.kind, recordId, budgetId, remote.version))
    }

    suspend fun resolve(change: PendingChange, keepLocal: Boolean) = lock.withLock {
        val session = auth.current() ?: throw CloudError(401, "Sign in first.")
        repo.db.withTransaction {
            val budget = (if (keepLocal) repo.writable(change.budgetId, false) else null)
                ?: dao.budget(change.budgetId)?.takeIf { it.principal == session.userId }
                ?: throw CloudError(403, "The budget is no longer available.")
            val current = dao.pending(change.kind, change.recordId) ?: return@withTransaction
            val version = current.remoteVersion ?: return@withTransaction
            if (keepLocal) {
                if (current.kind in listOf("budget", "account", "category")) repo.writable(budget.id, true)
                dao.put(current.copy(baseVersion = version, operationId = id(), remoteVersion = null, remoteBody = null, failure = null))
            } else {
                val body = current.remoteBody?.let { ledgerJson.parseToJsonElement(it).jsonObject }
                    ?: throw CloudError(409, "A server copy is unavailable. Retry synchronization.")
                saveRemote(session, budget.role, RemoteRecord(change.kind, version, body))
                dao.removePending(change.kind, change.recordId)
            }
        }
    }

    suspend fun invite(budget: String, role: Role): String = invitationService.create(budget, role) { session ->
        val local = dao.budget(budget)
        if (local == null || local.principal == null) throw InvitationFailure(InvitationError.BUDGET_NOT_SYNCED)
        if (local.principal != session.userId || local.type != BudgetType.SHARED || local.deletedAt != null ||
            local.role != Role.OWNER || role == Role.OWNER) throw InvitationFailure(InvitationError.PERMISSION_DENIED)
        if (dao.version("budget", budget) == null) {
            synchronize()
            if (dao.version("budget", budget) == null) throw InvitationFailure(InvitationError.BUDGET_NOT_SYNCED)
        }
    }

    suspend fun invitations(budget: String): List<Invitation> = ledgerJson.decodeFromJsonElement(
        rpc("ledger_invitations", buildJsonObject { put("p_budget", budget) })
    )

    suspend fun revokeInvitation(budget: String, invitation: String) {
        rpc("ledger_revoke_invite", buildJsonObject { put("p_budget", budget); put("p_id", invitation) })
    }

    suspend fun memberAccess(budget: String, user: String, role: String) {
        rpc("ledger_member_access", buildJsonObject { put("p_budget", budget); put("p_user", user); put("p_role", role) })
        synchronize()
    }

    suspend fun join(input: String): JoinedBudget {
        val membership = invitationService.redeem(input)
        val downloaded = try {
            synchronize()
            repo.db.withTransaction {
                if (repo.principal.value != membership.userId) return@withTransaction false
                val budget = dao.budget(membership.budgetId)
                val ready = budget?.principal == membership.userId && budget.deletedAt == null &&
                    dao.version("snapshot", membership.budgetId) != null
                // Keep the requested selection so the next successful pull opens the budget.
                repo.preferences { it.copy(activeBudget = membership.budgetId) }
                ready
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SharedLedgerInvite", "rpc=ledger_snapshot outcome=LOCAL_ERROR category=${e.javaClass.simpleName}")
            false
        }
        Log.i("SharedLedgerInvite", "rpc=ledger_snapshot outcome=${if (downloaded) "READY" else "SYNC_PENDING"}")
        if (!downloaded) requestSync()
        return JoinedBudget(membership.alreadyMember, downloaded)
    }

    private suspend fun rpc(name: String, body: JsonObject): JsonElement {
        val session = auth.current() ?: throw CloudError(401, "Sign in first.")
        return api.rpc(name, session.access, body)
    }
}
