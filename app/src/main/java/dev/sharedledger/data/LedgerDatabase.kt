package dev.sharedledger.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    @Query("SELECT * FROM budgets WHERE deletedAt IS NULL AND (principal IS NULL OR principal = :principal) ORDER BY createdAt, id")
    fun budgets(principal: String?): Flow<List<Budget>>
    @Query("SELECT * FROM budgets WHERE id = :id")
    suspend fun budget(id: String): Budget?
    @Query("SELECT * FROM budgets WHERE principal = :principal")
    suspend fun protectedBudgets(principal: String): List<Budget>
    @Query("SELECT * FROM budgets WHERE deletedAt IS NULL AND principal IS NULL LIMIT 1")
    suspend fun localBudget(): Budget?
    @Query("SELECT * FROM budgets WHERE principal IS NULL")
    suspend fun localBudgets(): List<Budget>
    @Query("DELETE FROM rates")
    suspend fun clearRates()
    @Query("SELECT * FROM accounts WHERE budgetId = :budget ORDER BY `order`, name")
    fun accountsFlow(budget: String): Flow<List<Account>>
    @Query("SELECT * FROM accounts WHERE budgetId = :budget ORDER BY `order`, name")
    suspend fun accounts(budget: String): List<Account>
    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun account(id: String): Account?
    @Query("SELECT * FROM categories WHERE budgetId = :budget ORDER BY `order`, name")
    fun categoriesFlow(budget: String): Flow<List<Category>>
    @Query("SELECT * FROM categories WHERE budgetId = :budget ORDER BY `order`, name")
    suspend fun categories(budget: String): List<Category>
    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun category(id: String): Category?
    @Query("SELECT * FROM entries WHERE budgetId = :budget AND deletedAt IS NULL ORDER BY date DESC, createdAt DESC")
    fun entriesFlow(budget: String): Flow<List<Entry>>
    @Query("SELECT * FROM entries WHERE budgetId = :budget ORDER BY date DESC, createdAt DESC")
    suspend fun entries(budget: String): List<Entry>
    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun entry(id: String): Entry?
    @Query("""SELECT * FROM entries WHERE budgetId = :budget AND accountId = :account AND currency = :currency
        AND accountCurrency = :accountCurrency AND type = :direction AND deletedAt IS NULL AND bankConversion IS NOT NULL
        AND date <= :day ORDER BY date DESC, id DESC""")
    suspend fun bankEntries(budget: String, account: String, currency: String, accountCurrency: String,
                            direction: dev.sharedledger.domain.EntryType, day: String): List<Entry>
    @Query("""SELECT budgetRate AS value, budgetRateDate AS date, budgetRateSource AS source FROM entries
        WHERE budgetId = :budgetId AND accountId = :accountId AND currency = :original AND budgetCurrency = :budget
        AND type = :direction AND deletedAt IS NULL AND date <= :day
        AND budgetRateConfirmedAt IS NOT NULL AND budgetRateSource = 'User-defined'
        AND budgetRate IS NOT NULL AND budgetRateDate IS NOT NULL AND (:except IS NULL OR id != :except)
        ORDER BY date DESC, budgetRateConfirmedAt DESC, id DESC LIMIT 1""")
    suspend fun latestBudgetRate(budgetId: String, accountId: String, original: String, budget: String,
                                 direction: dev.sharedledger.domain.EntryType, day: String, except: String?): RateQuote?
    @Query("SELECT * FROM rules WHERE budgetId = :budget AND deletedAt IS NULL ORDER BY start")
    fun rulesFlow(budget: String): Flow<List<Rule>>
    @Query("SELECT * FROM rules WHERE budgetId = :budget")
    suspend fun rules(budget: String): List<Rule>
    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun rule(id: String): Rule?
    @Query("SELECT * FROM rules WHERE active AND deletedAt IS NULL")
    suspend fun activeRules(): List<Rule>
    @Query("""SELECT * FROM rates WHERE base = :base AND target = :target AND
        (date = :day OR (date, source) IN (
            SELECT rateDate, source FROM rate_checks WHERE base = :base AND target = :target AND requestedDate = :day
        )) ORDER BY (source = 'Manual') DESC, date DESC, (source = 'Frankfurter') DESC, fetchedAt DESC LIMIT 1""")
    suspend fun rate(base: String, target: String, day: String): Rate?
    @Query("""SELECT * FROM rates WHERE base = :base AND target = :target AND date BETWEEN :start AND :end
        ORDER BY date DESC, (source = 'Frankfurter') DESC, fetchedAt DESC""")
    suspend fun rateWindow(base: String, target: String, start: String, end: String): List<Rate>
    @Query("""SELECT * FROM rates WHERE date BETWEEN :start AND :end AND
        ((base = :base AND target IN (:targets)) OR (target = :base AND base IN (:targets)))""")
    fun observeRateWindow(base: String, targets: List<String>, start: String, end: String): Flow<List<Rate>>
    @Query("""SELECT r.* FROM rates r WHERE r.base = :base AND r.target IN (:targets) AND r.source != 'Manual'
        AND r.date = (SELECT max(q.date) FROM rates q
            WHERE q.base = r.base AND q.target = r.target AND q.date <= :day AND q.source != 'Manual')
        ORDER BY r.date DESC, (r.source = 'Frankfurter') DESC, r.fetchedAt DESC""")
    fun currentRates(base: String, targets: List<String>, day: String): Flow<List<Rate>>
    @Query("SELECT * FROM rates WHERE source = 'Manual' ORDER BY date DESC, fetchedAt DESC")
    fun manualRates(): Flow<List<Rate>>
    @Query("SELECT * FROM rate_checks WHERE base = :base AND target = :target AND requestedDate = :day")
    suspend fun rateCheck(base: String, target: String, day: String): RateCheck?
    @Query("DELETE FROM rate_checks")
    suspend fun clearRateChecks()
    @Query("DELETE FROM rate_sync")
    suspend fun clearRateSync()
    @Query("SELECT * FROM rate_sync WHERE `key` = 0")
    suspend fun rateSync(): RateSyncRecord?
    @Query("SELECT * FROM rate_sync WHERE `key` = 0")
    fun rateSyncFlow(): Flow<RateSyncRecord?>
    @Query("""SELECT b.currency FROM budgets b WHERE b.deletedAt IS NULL AND (b.principal IS NULL OR b.principal = :principal)
        UNION SELECT a.currency FROM accounts a JOIN budgets b ON a.budgetId = b.id
        WHERE a.deletedAt IS NULL AND NOT a.archived AND b.deletedAt IS NULL AND (b.principal IS NULL OR b.principal = :principal)""")
    fun trackedCurrencies(principal: String?): Flow<List<String>>
    @Query("""SELECT e.* FROM entries e JOIN budgets b ON e.budgetId = b.id WHERE
        b.deletedAt IS NULL AND (b.principal IS NULL OR b.principal = :principal) AND b.role != 'VIEWER' AND
        e.deletedAt IS NULL AND e.type != 'TRANSFER' AND (e.accountAmount IS NULL OR e.budgetAmount IS NULL)
        AND NOT EXISTS (SELECT 1 FROM outbox o WHERE o.kind = 'entry' AND o.recordId = e.id AND o.remoteVersion IS NOT NULL)
        ORDER BY e.date, e.id""")
    fun unconvertedEntries(principal: String?): Flow<List<Entry>>
    @Query("SELECT * FROM settings WHERE `key` = 0")
    fun preferencesFlow(): Flow<Preferences?>
    @Query("SELECT * FROM settings WHERE `key` = 0")
    suspend fun preferences(): Preferences?
    @Query("SELECT * FROM members WHERE budgetId = :budget ORDER BY active DESC, name")
    fun members(budget: String): Flow<List<Member>>
    @Query("SELECT * FROM members WHERE budgetId = :budget ORDER BY active DESC, name")
    suspend fun membersOnce(budget: String): List<Member>
    @Query("SELECT * FROM members WHERE budgetId = :budget AND userId = :user AND active")
    suspend fun member(budget: String, user: String): Member?
    @Query("SELECT * FROM outbox ORDER BY budgetId, CASE kind WHEN 'budget' THEN 0 WHEN 'account' THEN 1 WHEN 'category' THEN 2 WHEN 'rule' THEN 3 ELSE 4 END, recordId")
    suspend fun pending(): List<PendingChange>
    @Query("SELECT * FROM outbox")
    fun pendingFlow(): Flow<List<PendingChange>>
    @Query("SELECT * FROM outbox WHERE budgetId = :budget AND imported")
    fun pendingImports(budget: String): Flow<List<PendingChange>>
    @Query("SELECT * FROM import_links WHERE budgetId = :budget AND sourceBudgetId = :source")
    suspend fun importLinks(budget: String, source: String): List<ImportLink>
    @Query("SELECT * FROM outbox WHERE kind = :kind AND recordId = :record")
    suspend fun pending(kind: String, record: String): PendingChange?
    @Query("SELECT version FROM remote_versions WHERE kind = :kind AND recordId = :record")
    suspend fun version(kind: String, record: String): Long?
    @Query("DELETE FROM outbox WHERE kind = :kind AND recordId = :record")
    suspend fun removePending(kind: String, record: String)
    @Query("DELETE FROM outbox WHERE budgetId = :budget")
    suspend fun removeBudgetChanges(budget: String)
    @Query("DELETE FROM outbox WHERE principal = :principal")
    suspend fun removePrincipalChanges(principal: String)
    @Query("SELECT syncedAt FROM sync_checkpoints WHERE principal = :principal")
    suspend fun lastSync(principal: String): Long?
    @Query("SELECT syncedAt FROM sync_checkpoints WHERE principal = :principal")
    fun lastSyncFlow(principal: String): Flow<Long?>
    @Upsert suspend fun put(value: SyncCheckpoint)
    @Query("DELETE FROM sync_checkpoints WHERE principal = :principal")
    suspend fun removeSyncCheckpoint(principal: String)
    @Query("DELETE FROM remote_versions WHERE budgetId = :budget")
    suspend fun removeVersions(budget: String)
    @Query("DELETE FROM members WHERE budgetId = :budget")
    suspend fun removeMembers(budget: String)
    @Query("DELETE FROM budgets WHERE id = :budget")
    suspend fun purge(budget: String)

    @Upsert suspend fun put(value: Budget)
    @Upsert suspend fun put(value: Account)
    @Upsert suspend fun put(value: Category)
    @Upsert suspend fun put(value: Entry)
    @Upsert suspend fun put(value: Rule)
    @Upsert suspend fun put(value: Rate)
    @Upsert suspend fun putRates(values: List<Rate>)
    @Upsert suspend fun put(value: RateCheck)
    @Upsert suspend fun put(value: RateSyncRecord)
    @Upsert suspend fun put(value: Member)
    @Upsert suspend fun put(value: Preferences)
    @Upsert suspend fun put(value: PendingChange)
    @Upsert suspend fun put(value: RemoteVersion)
    @Upsert suspend fun putImportLinks(values: List<ImportLink>)
}

@Database(
    entities = [Budget::class, Account::class, Category::class, Entry::class, Rule::class, Rate::class, Member::class,
        Preferences::class, PendingChange::class, RemoteVersion::class, RateCheck::class, RateSyncRecord::class, ImportLink::class,
        BankLearningReset::class, SyncCheckpoint::class],
    version = 7,
    exportSchema = true
)
@TypeConverters(BankConversionConverter::class)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun ledger(): LedgerDao

    companion object {
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN budgetRateConfirmedAt INTEGER")
            }
        }

        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outbox ADD COLUMN principal TEXT")
                db.execSQL("ALTER TABLE outbox ADD COLUMN failure TEXT")
                db.execSQL("UPDATE outbox SET principal = (SELECT principal FROM budgets WHERE budgets.id = outbox.budgetId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_checkpoints (principal TEXT NOT NULL, syncedAt INTEGER NOT NULL, PRIMARY KEY (principal))")
                db.execSQL("DELETE FROM remote_versions WHERE kind = 'snapshot'")
            }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entries ADD COLUMN bankConversion TEXT")
                db.execSQL("ALTER TABLE settings ADD COLUMN smartBankRates INTEGER NOT NULL DEFAULT 1")
                db.execSQL("""CREATE TABLE IF NOT EXISTS bank_learning_resets (
                    accountId TEXT NOT NULL, currency TEXT NOT NULL, accountCurrency TEXT NOT NULL, direction TEXT NOT NULL,
                    resetAt INTEGER NOT NULL, PRIMARY KEY (accountId, currency, accountCurrency, direction),
                    FOREIGN KEY (accountId) REFERENCES accounts (id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outbox ADD COLUMN imported INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""CREATE TABLE IF NOT EXISTS import_links (
                    budgetId TEXT NOT NULL, sourceBudgetId TEXT NOT NULL, kind TEXT NOT NULL, sourceId TEXT NOT NULL,
                    targetId TEXT NOT NULL, PRIMARY KEY (budgetId, sourceBudgetId, kind, sourceId),
                    FOREIGN KEY (budgetId) REFERENCES budgets (id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
            }
        }

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_entries_budgetId_payer_date ON entries (budgetId, payer, date)")
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE rates_new (base TEXT NOT NULL, target TEXT NOT NULL, requestedDate TEXT NOT NULL,
                    date TEXT NOT NULL, value TEXT NOT NULL, source TEXT NOT NULL, fetchedAt INTEGER NOT NULL,
                    PRIMARY KEY (base, target, date, source))""")
                db.execSQL("""INSERT OR REPLACE INTO rates_new SELECT base, target, requestedDate, date, value, source, fetchedAt
                    FROM rates ORDER BY fetchedAt, requestedDate""")
                db.execSQL("""CREATE TABLE rate_checks (base TEXT NOT NULL, target TEXT NOT NULL, requestedDate TEXT NOT NULL,
                    rateDate TEXT, source TEXT, checkedAt INTEGER NOT NULL, retryable INTEGER NOT NULL,
                    PRIMARY KEY (base, target, requestedDate))""")
                db.execSQL("""INSERT OR REPLACE INTO rate_checks (base, target, requestedDate, rateDate, source, checkedAt, retryable)
                    SELECT base, target, requestedDate, date, source, fetchedAt, 0 FROM rates
                    WHERE source != 'Manual' AND date <= requestedDate AND date >= DATE(requestedDate, '-7 days')
                    ORDER BY date, (source = 'Frankfurter'), fetchedAt""")
                db.execSQL("DROP TABLE rates")
                db.execSQL("ALTER TABLE rates_new RENAME TO rates")
                db.execSQL("""CREATE TABLE rate_sync (`key` INTEGER NOT NULL, lastSuccess INTEGER, successDate TEXT,
                    lastAttempt INTEGER, incomplete INTEGER NOT NULL, retryable INTEGER NOT NULL, PRIMARY KEY (`key`))""")
                db.execSQL("ALTER TABLE settings ADD COLUMN automaticRates INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE outbox ADD COLUMN rateOnly INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
