package dev.sharedledger.sync

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sharedledger.data.*
import dev.sharedledger.domain.*
import dev.sharedledger.presentation.slices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule as TestRule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DeletionSyncTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @get:TestRule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LedgerDatabase::class.java)

    @Test fun offlineLegacyDeletionSurvivesRestartAndRejectionThenWinsOverConcurrentEdits() = runBlocking {
        val server = Server()
        val databaseName = "pending-delete-${id()}"
        Client(server, EDITOR, Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()).use { editor ->
            fun owner() = Client(server, OWNER, Room.databaseBuilder(context, LedgerDatabase::class.java, databaseName).build())
            try {
                owner().use { first ->
                    assertTrue(first.sync.synchronize())
                    assertTrue(editor.sync.synchronize())
                    assertEquals(BigDecimal("21.5"), first.usdBalance())
                    assertEquals(BigDecimal("2500"), editor.totals().expenses)
                    assertEquals(BigDecimal("2500"), editor.groups().single().totals.expenses)
                    assertEquals(BigDecimal("2500"), editor.expenseSlices().single().amount)
                    assertEquals(2, editor.groups().single().entries.size)
                    val entry = checkNotNull(first.dao.entry(EXPENSE))
                    val legacy = entry.copy(accountRateDate = null)
                    first.dao.put(legacy)
                    first.repo.deleteEntry(legacy)
                    val pending = checkNotNull(first.dao.pending("entry", EXPENSE))
                    first.repo.deleteEntry(legacy)
                    assertEquals(pending, first.dao.pending("entry", EXPENSE))
                    assertNull(pending.payload()?.string("accountRateDate"))
                    assertNotNull(pending.payload()?.string("accountAmount"))
                    assertTrue(first.dao.entriesFlow(BUDGET).first().none { it.id == EXPENSE })
                    assertEquals(0, first.groups().single().totals.outgoing.count)
                    assertEquals(BigDecimal("43"), first.usdBalance())
                    server.offline.add(OWNER)
                    assertFalse(first.sync.synchronize())
                    assertEquals(pending, first.dao.pending("entry", EXPENSE))
                }

                owner().use { restored ->
                    assertNotNull(restored.dao.entry(EXPENSE)?.deletedAt)
                    assertNotNull(restored.dao.lastSync(OWNER))
                    server.offline.clear()
                    server.reject.add(EXPENSE)
                    restored.repo.deleteEntry(checkNotNull(restored.dao.entry(INCOME)))
                    assertTrue(restored.sync.synchronize())
                    val failed = checkNotNull(restored.dao.pending("entry", EXPENSE))
                    assertEquals("CONSTRAINT", failed.failure)
                    assertTrue(restored.sync.status.value.text.contains("deletion"))
                    assertTrue(restored.dao.entriesFlow(BUDGET).first().isEmpty())
                    assertNotNull(server.body(INCOME).string("deletedAt"))
                    assertEquals(setOf("deletedAt"), server.writes.getValue(EXPENSE).single().keys)
                    assertTrue(restored.sync.synchronize())
                    assertEquals(1, server.writes.getValue(EXPENSE).size)
                    assertTrue(editor.sync.synchronize())
                    assertEquals(BigDecimal.ZERO, editor.totals().income)
                    assertEquals(BigDecimal("2500"), editor.totals().expenses)
                    assertEquals(listOf(EXPENSE), editor.groups().single().entries.map(Entry::id))

                    server.reject.clear()
                    editor.editExpense("Accepted edit")
                    assertTrue(editor.sync.synchronize())
                    editor.editExpense("Offline stale edit")
                    assertTrue(restored.sync.synchronize(retryFailed = true))
                    assertEquals("Up to date", restored.sync.status.value.text)
                    assertTrue(editor.sync.synchronize())
                    for (client in listOf(restored, editor)) {
                        assertTrue(client.dao.pending().isEmpty())
                        assertEquals("Accepted edit", client.dao.entry(EXPENSE)?.note)
                        assertNotNull(client.dao.entry(EXPENSE)?.deletedAt)
                        assertEquals(BigDecimal.ZERO, client.usdBalance())
                        assertEquals(BigDecimal.ZERO, client.totals().expenses)
                        assertTrue(client.groups().isEmpty())
                        assertTrue(client.expenseSlices().isEmpty())
                        assertNull(client.repo.bankRates.offer(BankPair(BUDGET, ACCOUNT, "RSD", "USD", EntryType.EXPENSE), DAY))
                    }
                    assertEquals(3, server.writes.getValue(EXPENSE).size)
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }
    }

    @Test fun rejectedParentRetainsItsDependentWritesAndAnIndependentRecordCanSync() = runBlocking {
        val server = Server()
        Client(server, OWNER, Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()).use { client ->
            assertTrue(client.sync.synchronize())
            val account = Account(id(), BUDGET, "New account", "RSD")
            server.reject.add(account.id)
            client.repo.saveAccount(account)
            client.repo.saveEntry(BUDGET, EntryDraft(EntryType.EXPENSE, BigDecimal("20"), DAY, account.id, CATEGORY, "RSD"))
            val dependent = client.dao.entries(BUDGET).single { it.accountId == account.id }.id
            client.repo.deleteEntry(checkNotNull(client.dao.entry(INCOME)))
            assertTrue(client.sync.synchronize())
            assertEquals("CONSTRAINT", client.dao.pending("account", account.id)?.failure)
            assertNull(server.writes[dependent])
            assertNotNull(client.dao.pending("entry", dependent))
            assertNotNull(server.body(INCOME).string("deletedAt"))
            assertNull(client.dao.pending("entry", INCOME))
            server.reject.clear()
            assertTrue(client.sync.synchronize(retryFailed = true))
            assertTrue(client.dao.pending().isEmpty())
            assertEquals("20", server.body(dependent).string("amount"))
        }
    }

    @Test fun permissionLossHidesTheBudgetButRetainsTheOriginalPrincipalAndDeleteIntent() = runBlocking {
        val server = Server()
        Client(server, EDITOR, Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()).use { client ->
            assertTrue(client.sync.synchronize())
            client.repo.deleteEntry(checkNotNull(client.dao.entry(EXPENSE)))
            server.revoked.add(EDITOR)
            assertTrue(client.sync.synchronize())
            assertNull(client.dao.budget(BUDGET))
            val failed = checkNotNull(client.dao.pending("entry", EXPENSE))
            assertEquals("PERMISSION", failed.failure)
            assertEquals(EDITOR, failed.principal)
            assertTrue(failed.deletesEntry())
            assertNull(server.writes[EXPENSE])
            server.revoked.clear()
            assertTrue(client.sync.synchronize(retryFailed = true))
            assertNull(client.dao.pending("entry", EXPENSE))
            assertNotNull(client.dao.entry(EXPENSE)?.deletedAt)
            assertTrue(client.dao.entriesFlow(BUDGET).first().none { it.id == EXPENSE })
        }
    }

    @Test fun migrationScopesPreviouslyQueuedDeletionsWithoutChangingTheirPayload() {
        val body = """{"deletedAt":1795220000000,"accountAmount":"21.50"}"""
        val name = "outbox-upgrade"
        migration.createDatabase(name, 5).use {
            it.execSQL("INSERT INTO budgets (id,name,currency,type,icon,color,role,principal,createdAt,updatedAt) VALUES ('b','Shared','RSD','SHARED','wallet',1,'OWNER','owner',1,1)")
            it.execSQL("INSERT INTO outbox (kind,recordId,budgetId,operationId,body,baseVersion) VALUES ('entry','e','b','op',?,1)", arrayOf(body))
            it.execSQL("INSERT INTO remote_versions (kind,recordId,budgetId,version) VALUES ('snapshot','b','b',10),('entry','e','b',1)")
        }
        migration.runMigrationsAndValidate(name, 6, true, LedgerDatabase.MIGRATION_5_6).use {
            it.query("SELECT principal,body,failure,operationId FROM outbox").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(OWNER, cursor.getString(0))
                assertEquals(body, cursor.getString(1))
                assertTrue(cursor.isNull(2))
                assertEquals("op", cursor.getString(3))
            }
            it.query("SELECT kind,version FROM remote_versions").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("entry", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertFalse(cursor.moveToNext())
            }
        }
    }

    private class Client(server: Server, user: String, private val db: LedgerDatabase) : AutoCloseable {
        val repo = LedgerRepository(db, Rates(db, emptyList()))
        init { repo.principal.value = user }
        val dao = repo.dao
        private val api = CloudApi(server.http, "https://ledger.example.test", "fixture")
        private val auth = CloudAuth(api, object : AuthVault {
            override fun read() = Session(user, user, "refresh", Long.MAX_VALUE)
            override fun write(session: Session) = Unit
            override fun clear() = Unit
            override fun readValue(name: String): String? = null
            override fun writeValue(name: String, value: String) = Unit
            override fun clearValue(name: String) = Unit
        }, { repo.principal.value = it }, { error("Unexpected account purge") })
        val sync = SyncEngine(api, auth, repo) { }

        suspend fun usdBalance(): BigDecimal = accountBalance(checkNotNull(dao.account(ACCOUNT)), dao.entries(BUDGET)).value
        suspend fun totals(): Totals = totals(dao.entries(BUDGET), "RSD")
        suspend fun groups() = transactionGroups(dao.entriesFlow(BUDGET).first(), "RSD", Entry::date)
        suspend fun expenseSlices() = slices(dao.entriesFlow(BUDGET).first(), "RSD", EntryType.EXPENSE, dao.categories(BUDGET))
        suspend fun editExpense(note: String) =
            repo.saveEntry(BUDGET, EntryDraft(EntryType.EXPENSE, BigDecimal("2500"), DAY, ACCOUNT, CATEGORY, "RSD", note), EXPENSE)
        override fun close() = db.close()
    }

    private data class ServerRow(val kind: String, val body: JsonObject, val version: Long = 1, val operation: String? = null) {
        fun envelope() = buildJsonObject {
            put("kind", kind); put("body", body); put("version", version); operation?.let { put("operation", it) }
        }
    }

    private class Server {
        private var sequence = 1L
        private val members = ledgerJson.encodeToJsonElement(listOf(
            Member(BUDGET, OWNER, "Owner", Role.OWNER), Member(BUDGET, EDITOR, "Editor", Role.EDITOR)
        ))
        private val rows = listOf(
            row("budget", Budget(BUDGET, "Household", "RSD", BudgetType.SHARED, owner = OWNER)),
            row("account", Account(ACCOUNT, BUDGET, "USD card", "USD")),
            row("category", Category(CATEGORY, BUDGET, "Groceries", EntryType.EXPENSE, "cart", 1)),
            row("category", Category("salary", BUDGET, "Salary", EntryType.INCOME, "wallet", 1)),
            row("entry", Entry(EXPENSE, BUDGET, ACCOUNT, CATEGORY, EntryType.EXPENSE, "2500", "RSD", "USD", "RSD",
                DAY.toString(), accountAmount = "21.5", budgetAmount = "2500", accountRate = "0.0086",
                accountRateDate = DAY.toString(), accountRateSource = "Bank actual", budgetRate = "1",
                budgetRateDate = DAY.toString(), budgetRateSource = "Same currency",
                bankConversion = BankConversion(BankSource.BANK_ACTUAL, confirmedAt = 1795220000000))),
            row("entry", Entry(INCOME, BUDGET, ACCOUNT, "salary", EntryType.INCOME, "43", "USD", "USD", "RSD", DAY.toString(),
                accountAmount = "43", accountRate = "1", accountRateDate = DAY.toString(), accountRateSource = "Same currency"))
        ).associateByTo(linkedMapOf()) { it.body.string("id")!! }
        val offline = hashSetOf<String>()
        val reject = hashSetOf<String>()
        val revoked = hashSetOf<String>()
        val writes = mutableMapOf<String, MutableList<JsonObject>>()
        fun body(id: String) = rows.getValue(id).body

        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val user = request.header("Authorization")?.removePrefix("Bearer ")
            if (user in offline) throw java.io.IOException("Fixture offline")
            when (request.url.pathSegments.last()) {
                "ledger_manifest" -> reply(request, buildJsonArray {
                    if (user !in revoked) add(buildJsonObject {
                        put("id", BUDGET); put("seq", sequence); put("role", if (user == OWNER) "OWNER" else "EDITOR")
                    })
                })
                "ledger_snapshot" -> reply(request, buildJsonObject {
                    put("seq", sequence); put("records", JsonArray(rows.values.map(ServerRow::envelope))); put("members", members)
                })
                "ledger_apply" -> apply(request)
                else -> error("Unexpected RPC")
            }
        }.build()

        private fun apply(request: Request): Response {
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val args = ledgerJson.parseToJsonElement(buffer.readUtf8()).jsonObject
            val id = args.string("p_id")!!
            val body = args.getValue("p_body").jsonObject
            writes.getOrPut(id, ::mutableListOf).add(body)
            if (id in reject) return reply(request, buildJsonObject {
                put("code", "23514")
                put("message", "new row for relation violates check constraint \"transactions_check2\"")
            }, 400)
            val old = rows[id]
            val deletes = body.string("deletedAt") != null
            val conflict = old != null && !deletes && (old.body.string("deletedAt") != null ||
                old.version != args.getValue("p_base").jsonPrimitive.long)
            val changed = if (conflict) checkNotNull(old) else {
                val saved = if (deletes) JsonObject(checkNotNull(old).body + body) else body
                ServerRow(args.string("p_kind")!!, saved, (old?.version ?: 0) + 1, args.string("p_operation")).also {
                    sequence++
                    rows[id] = it
                }
            }
            return reply(request, JsonObject(changed.envelope() + ("status" to JsonPrimitive(if (conflict) "conflict" else "ok"))))
        }

        private fun reply(request: Request, json: JsonElement, status: Int = 200): Response =
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .body(json.toString().toResponseBody("application/json".toMediaType())).build()
    }

    private companion object {
        const val BUDGET = "household"
        const val OWNER = "owner"
        const val EDITOR = "editor"
        const val ACCOUNT = "card"
        const val CATEGORY = "groceries"
        const val EXPENSE = "purchase"
        const val INCOME = "income"
        val DAY: LocalDate = LocalDate.of(2026, 11, 20)
        inline fun <reified T> row(kind: String, value: T) = ServerRow(kind, ledgerJson.encodeToJsonElement(value).jsonObject)
    }
}
