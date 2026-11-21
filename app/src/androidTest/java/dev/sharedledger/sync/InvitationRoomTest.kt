package dev.sharedledger.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sharedledger.data.*
import dev.sharedledger.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InvitationRoomTest {
    @Test fun redeemingDownloadsAndSelectsTheBudgetAndItsFinancialRecords() = runBlocking {
        withFixture { fixture ->
            val joined = fixture.engine.join("Join my SharedLedger budget\nsharedledger://invite/$TOKEN\nInvitation code: $TOKEN")
            assertEquals(JoinedBudget(alreadyMember = false, downloaded = true), joined)
            fixture.assertBudgetReady()
            assertEquals(1, fixture.redemptions)
            assertEquals(0, fixture.retries)
        }
    }

    @Test fun connectionLossAfterRedemptionPreservesMembershipAndRetriesTheDownload() = runBlocking {
        withFixture { fixture ->
            fixture.available = false
            assertEquals(JoinedBudget(alreadyMember = false, downloaded = false), fixture.engine.join(TOKEN))
            assertEquals(1, fixture.redemptions)
            assertEquals(1, fixture.retries)
            assertNull(fixture.repository.dao.budget(BUDGET))
            assertEquals(BUDGET, fixture.repository.dao.preferences()?.activeBudget)
            fixture.available = true
            assertTrue(fixture.engine.synchronize())
            fixture.assertBudgetReady()
            assertEquals(1, fixture.redemptions)
            assertEquals(JoinedBudget(alreadyMember = true, downloaded = true), fixture.engine.join(TOKEN))
        }
    }

    @Test fun localBudgetIsRejectedBeforeContactingTheInvitationRpc() = runBlocking {
        withFixture { fixture ->
            val personal = checkNotNull(fixture.repository.dao.localBudget())
            try {
                fixture.engine.invite(personal.id, Role.EDITOR)
                fail("Local budget produced a cloud invitation")
            } catch (e: InvitationFailure) {
                assertEquals(InvitationError.BUDGET_NOT_SYNCED, e.error)
                assertTrue(fixture.operations.isEmpty())
            }
        }
    }

    private suspend fun withFixture(test: suspend (Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val repository = LedgerRepository(db, Rates(db, emptyList()))
            repository.initialize()
            test(Fixture(repository))
        } finally {
            db.close()
        }
    }

    private class Fixture(val repository: LedgerRepository) {
        var retries = 0
        var redemptions = 0
        var available = true
        val operations = mutableListOf<String>()
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val operation = request.url.pathSegments.last()
            operations += operation
            assertEquals("Bearer editor-access", request.header("Authorization"))
            val reply = when (operation) {
                "ledger_redeem" -> buildJsonObject {
                    redemptions++
                    put("code", if (redemptions == 1) "JOINED" else "ALREADY_MEMBER")
                    put("budgetId", BUDGET)
                }
                "ledger_manifest" -> buildJsonArray {
                    add(buildJsonObject { put("id", BUDGET); put("seq", 5); put("role", "EDITOR") })
                }
                "ledger_snapshot" -> snapshot
                else -> error("Unexpected request")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (operation != "ledger_redeem" && !available) 503 else 200).message("Test response")
                .body(reply.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        private val api = CloudApi(client, "https://ledger.example.test", "fixture")
        private val auth = CloudAuth(api, object : AuthVault {
            private var session: Session? = Session(EDITOR, "editor-access", "editor-refresh", Long.MAX_VALUE)
            override fun read() = session
            override fun write(session: Session) { this.session = session }
            override fun clear() { session = null }
            override fun readValue(name: String): String? = null
            override fun writeValue(name: String, value: String) { error("Unexpected Auth write") }
            override fun clearValue(name: String) { error("Unexpected Auth clear") }
        }, { repository.principal.value = it }, { error("Unexpected account purge") })
        val engine = SyncEngine(api, auth, repository) { retries++ }

        suspend fun assertBudgetReady() {
            val dao = repository.dao
            val budget = dao.budgets(EDITOR).first().single { it.id == BUDGET }
            assertEquals(Role.EDITOR, budget.role)
            assertEquals(BUDGET, dao.preferences()?.activeBudget)
            assertEquals("Cash", dao.accounts(BUDGET).single().name)
            assertEquals("Groceries", dao.categories(BUDGET).single().name)
            assertEquals("125", dao.entries(BUDGET).single().budgetAmount)
        }
    }

    private companion object {
        val TOKEN = "abcdef0123456789".repeat(4)
        const val BUDGET = "10000000-0000-0000-0000-000000000001"
        const val EDITOR = "editor"
        const val OWNER = "owner"
        const val ACCOUNT = "cash"
        const val CATEGORY = "groceries"

        val snapshot = buildJsonObject {
            put("seq", 5)
            put("records", buildJsonArray {
                add(record("budget", ledgerJson.encodeToJsonElement(Budget(BUDGET, "Household", "RSD", BudgetType.SHARED, owner = OWNER))))
                add(record("account", ledgerJson.encodeToJsonElement(Account(ACCOUNT, BUDGET, "Cash", "RSD"))))
                add(record("category", ledgerJson.encodeToJsonElement(Category(CATEGORY, BUDGET, "Groceries", EntryType.EXPENSE, "cart", 1))))
                val entry = Entry("expense", BUDGET, ACCOUNT, CATEGORY, EntryType.EXPENSE, "125", "RSD", "RSD", "RSD",
                    "2026-11-20", accountAmount = "125", budgetAmount = "125", creator = OWNER)
                add(record("entry", ledgerJson.encodeToJsonElement(entry)))
            })
            put("members", ledgerJson.encodeToJsonElement(listOf(
                Member(BUDGET, OWNER, "Owner", Role.OWNER), Member(BUDGET, EDITOR, "Editor", Role.EDITOR)
            )))
        }

        fun record(kind: String, body: JsonElement) = buildJsonObject {
            put("kind", kind)
            put("version", 1)
            put("body", body)
        }
    }
}
