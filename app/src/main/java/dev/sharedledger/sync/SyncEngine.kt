package dev.sharedledger.sync

import androidx.room.withTransaction
import dev.sharedledger.data.*
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
class SyncEngine @Inject constructor(private val api: CloudApi, private val auth: CloudAuth, private val repo: LedgerRepository) {
    private val lock = Mutex()
    private val dao get() = repo.dao
    val status = MutableStateFlow(SyncStatus())
    val configured get() = api.configured

    suspend fun synchronize(): Boolean = lock.withLock {
        if (!configured) return true
        status.value = status.value.copy(running = true, text = "Checking for changes…")
        try {
            val session = auth.current()
            if (session == null) {
                status.value = SyncStatus(text = "Sign in to sync")
                return true
            }
            var warning = pull(session, keepLocalCreations = true)
            var issue: CloudError? = null
            val rejected = mutableSetOf<String>()
            for (change in dao.pending().filter { it.remoteVersion == null }) {
                if (change.budgetId in rejected || dao.budget(change.budgetId)?.principal != session.userId || repo.principal.value != session.userId) continue
                try {
                    val value = api.rpc("ledger_apply", session.access, buildJsonObject {
                        put("p_kind", change.kind); put("p_budget", change.budgetId); put("p_id", change.recordId)
                        put("p_base", change.baseVersion); put("p_operation", change.operationId)
                        put("p_body", ledgerJson.parseToJsonElement(change.body))
                    }).jsonObject
                    applyPush(session, change, value)
                } catch (e: CloudError) {
                    if (e.status == 401 || e.status == 0 || e.status >= 500 || e.status == 429) throw e
                    issue = e
                    rejected.add(change.budgetId)
                }
            }
            warning = pull(session, keepLocalCreations = true) ?: warning
            status.value = SyncStatus(text = warning ?: issue?.message ?: "Up to date", lastSync = System.currentTimeMillis())
            true
        } catch (e: CancellationException) {
            status.value = status.value.copy(running = false)
            throw e
        } catch (e: CloudError) {
            if (e.status == 401) try { auth.current(forceRefresh = true) } catch (_: CloudError) { }
            status.value = status.value.copy(running = false, text = e.message.orEmpty())
            e.status != 0 && e.status != 401 && e.status != 429 && e.status < 500
        } catch (e: Exception) {
            android.util.Log.w("SharedLedger", "Sync failed (${e.javaClass.simpleName})")
            status.value = status.value.copy(running = false, text = "Sync interrupted. Local changes are queued.")
            false
        }
    }

    private suspend fun applyPush(session: Session, sent: PendingChange, result: JsonObject) = repo.db.withTransaction {
        if (repo.principal.value != session.userId) return@withTransaction
        val current = dao.pending(sent.kind, sent.recordId) ?: return@withTransaction
        val version = result["version"]?.jsonPrimitive?.long ?: error("Missing version")
        val remote = result["body"] as? JsonObject
        val record = remote?.let { RemoteRecord(sent.kind, version, it) }
        val budget = dao.budget(sent.budgetId) ?: return@withTransaction
        if (result.string("status") == "ok") {
            dao.put(RemoteVersion(sent.kind, sent.recordId, sent.budgetId, version))
            if (current.operationId == sent.operationId) {
                if (record != null) saveRemote(session, budget.role, record)
                dao.removePending(sent.kind, sent.recordId)
            } else dao.put(current.copy(baseVersion = version, remoteVersion = null, remoteBody = null))
        } else if (record != null && (current.rateOnly || current.kind == "entry" && current.baseVersion == 0L &&
            record.body.string("ruleId") != null && record.body.string("deletedAt") == null && current.operationId == sent.operationId)) {
            saveRemote(session, budget.role, record)
            dao.removePending(sent.kind, sent.recordId)
        } else {
            dao.put(current.copy(remoteVersion = version, remoteBody = remote?.toString()))
        }
    }

    private suspend fun pull(session: Session, keepLocalCreations: Boolean): String? {
        val manifest: List<ManifestItem> = ledgerJson.decodeFromJsonElement(api.rpc("ledger_manifest", session.access))
        val accessible = manifest.mapTo(hashSetOf()) { it.id }
        var warning: String? = null
        repo.db.withTransaction {
            if (repo.principal.value != session.userId) return@withTransaction
            for (budget in dao.protectedBudgets(session.userId)) {
                if (budget.id in accessible) continue
                val creating = dao.pending("budget", budget.id)?.baseVersion == 0L
                if (!creating || !keepLocalCreations) {
                    if (budget.deletedAt == null && dao.pending().any { it.budgetId == budget.id }) {
                        warning = "Access was removed from a budget that had unsynced changes."
                    }
                    repo.purgeBudget(budget.id)
                }
            }
        }
        for (key in manifest) {
            if (repo.principal.value != session.userId) break
            if (dao.version("snapshot", key.id) == key.seq && dao.budget(key.id)?.role == key.role) continue
            val value = api.rpc("ledger_snapshot", session.access, buildJsonObject { put("p_budget", key.id) })
            if (value == JsonNull) continue
            val snapshot: Snapshot = ledgerJson.decodeFromJsonElement(value)
            repo.db.withTransaction {
                if (repo.principal.value != session.userId) return@withTransaction
                val role = snapshot.members.find { it.userId == session.userId && it.active }?.role ?: return@withTransaction
                for (remote in snapshot.records) {
                    val recordId = remote.body.string("id") ?: error("Missing id")
                    val local = dao.pending(remote.kind, recordId)
                    if (local == null) saveRemote(session, role, remote)
                    else if (local.operationId == remote.operation) {
                        saveRemote(session, role, remote)
                        dao.removePending(local.kind, local.recordId)
                    } else if (local.rateOnly && (local.baseVersion != remote.version || role == Role.VIEWER)) {
                        saveRemote(session, role, remote)
                        dao.removePending(local.kind, local.recordId)
                    } else if (local.baseVersion != remote.version) {
                        if (remote.kind == "entry" && local.baseVersion == 0L && remote.body.string("ruleId") != null &&
                            remote.body.string("deletedAt") == null) {
                            saveRemote(session, role, remote)
                            dao.removePending(remote.kind, recordId)
                        } else {
                            dao.put(local.copy(remoteVersion = remote.version, remoteBody = remote.body.toString()))
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
                dao.put(current.copy(baseVersion = version, operationId = id(), remoteVersion = null, remoteBody = null))
            } else {
                val body = current.remoteBody?.let { ledgerJson.parseToJsonElement(it).jsonObject }
                    ?: throw CloudError(409, "A server copy is unavailable. Retry synchronization.")
                saveRemote(session, budget.role, RemoteRecord(change.kind, version, body))
                dao.removePending(change.kind, change.recordId)
            }
        }
    }

    suspend fun invite(budget: String, role: Role): String = rpc("ledger_invite", buildJsonObject {
        put("p_budget", budget); put("p_role", role.name)
    }).jsonObject.string("token") ?: error("Missing invitation")

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

    suspend fun join(code: String): String {
        val token = code.trim().substringAfterLast('/').lowercase()
        val result = rpc("ledger_redeem", buildJsonObject { put("p_token", token) }).jsonObject
        result.string("error")?.let { throw CloudError(400, it) }
        val budget = result.string("budgetId") ?: error("Missing budget")
        synchronize()
        repo.preferences { it.copy(activeBudget = budget) }
        return budget
    }

    private suspend fun rpc(name: String, body: JsonObject): JsonElement {
        val session = auth.current() ?: throw CloudError(401, "Sign in first.")
        return api.rpc(name, session.access, body)
    }
}
