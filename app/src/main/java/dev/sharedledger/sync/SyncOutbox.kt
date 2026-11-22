package dev.sharedledger.sync

import dev.sharedledger.data.PendingChange
import dev.sharedledger.data.ledgerJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun PendingChange.payload(): JsonObject? =
    runCatching { ledgerJson.parseToJsonElement(body) as? JsonObject }.getOrNull()

internal fun PendingChange.deletesEntry(body: JsonObject? = payload()): Boolean =
    kind == "entry" && body?.string("deletedAt") != null

internal val PendingChange.key get() = kind to recordId

internal fun PendingChange.dependencies(body: JsonObject): List<Pair<String, String>> = buildList {
    if (kind == "budget") return@buildList
    add("budget" to budgetId)
    // A server-side deletion has no dependency on the locally edited relationships.
    if (deletesEntry(body) && (baseVersion > 0 || body.string("ruleId") == null)) return@buildList
    for ((kind, field) in listOf("account" to "accountId", "account" to "destinationId",
        "category" to "categoryId", "rule" to "ruleId")) {
        body.string(field)?.let { add(kind to it) }
    }
}

internal fun PendingChange.pushPayload(body: JsonObject): JsonObject =
    if (deletesEntry(body) && (baseVersion > 0 || body.string("ruleId") == null)) {
        buildJsonObject { put("deletedAt", body.getValue("deletedAt")) }
    } else body

internal enum class SyncFailure {
    PERMISSION, CONSTRAINT, INVALID, CONFLICT, REJECTED;

    companion object {
        fun from(e: CloudError): SyncFailure = when {
            e.status == 403 || e.code == "42501" -> PERMISSION
            e.code?.startsWith("23") == true -> CONSTRAINT
            e.code?.startsWith("22") == true || e.status == 400 -> INVALID
            e.status == 409 -> CONFLICT
            else -> REJECTED
        }
    }
}

internal val CloudError.isTransient get() = when {
    status == 0 || status == 401 || status == 408 || status == 429 -> true
    code?.take(2) in setOf("22", "23", "42") -> false
    else -> status >= 500
}

internal fun PendingChange.failureTitle(): String =
    if (deletesEntry()) "Transaction deletion needs attention" else "Change needs attention"

internal fun PendingChange.failureMessage(): String = when (failure) {
    SyncFailure.PERMISSION.name -> "This change is saved on this device. Ask the budget owner to restore editing access, then retry."
    else -> if (deletesEntry())
        "The transaction was deleted on this device. The server rejected the change. Update the Supabase migrations if needed, then retry sync."
    else "Your change is saved on this device. Review the saved record or resolve its dependencies, then retry sync."
}

internal fun CloudError.syncMessage(): String = when (status) {
    0, 408 -> "Couldn’t reach Supabase. Changes will sync when the connection returns."
    401 -> "Your session needs to be refreshed. Sign in again if sync doesn’t resume."
    403 -> "Supabase denied access. Check your account and budget permissions."
    429 -> "Supabase is limiting requests. Sync will retry."
    else -> "Supabase couldn’t complete synchronization. Your changes are saved on this device."
}

internal fun CloudError.constraintName(): String? =
    Regex("""constraint "([a-zA-Z0-9_]{1,63})"""").find(message.orEmpty())?.groupValues?.get(1)
