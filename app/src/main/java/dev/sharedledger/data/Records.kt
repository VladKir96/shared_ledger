package dev.sharedledger.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.sharedledger.domain.*
import kotlinx.serialization.Serializable

interface LedgerRecord {
    val id: String
    val updatedAt: Long
    val deletedAt: Long?
}

@Serializable
@Entity(tableName = "budgets", indices = [Index("principal")])
data class Budget(
    @PrimaryKey override val id: String,
    val name: String,
    val currency: String,
    val type: BudgetType = BudgetType.PERSONAL,
    val icon: String = "wallet",
    val color: Long = 0xFF397D63,
    val principal: String? = null,
    val role: Role = Role.OWNER,
    val owner: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = createdAt,
    override val deletedAt: Long? = null
) : LedgerRecord

@Serializable
@Entity(tableName = "accounts", foreignKeys = [ForeignKey(entity = Budget::class, parentColumns = ["id"], childColumns = ["budgetId"], onDelete = ForeignKey.CASCADE)], indices = [Index("budgetId")])
data class Account(
    @PrimaryKey override val id: String,
    val budgetId: String,
    val name: String,
    val currency: String,
    val type: AccountType = AccountType.CASH,
    val opening: String = "0",
    val icon: String = "wallet",
    val color: Long = 0xFF397D63,
    val order: Int = 0,
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = createdAt,
    override val deletedAt: Long? = null
) : LedgerRecord

@Serializable
@Entity(tableName = "categories", foreignKeys = [ForeignKey(entity = Budget::class, parentColumns = ["id"], childColumns = ["budgetId"], onDelete = ForeignKey.CASCADE)], indices = [Index("budgetId")])
data class Category(
    @PrimaryKey override val id: String,
    val budgetId: String,
    val name: String,
    val type: EntryType,
    val icon: String,
    val color: Long,
    val order: Int = 0,
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = createdAt,
    override val deletedAt: Long? = null
) : LedgerRecord

@Serializable
@Entity(
    tableName = "entries",
    foreignKeys = [
        ForeignKey(entity = Budget::class, parentColumns = ["id"], childColumns = ["budgetId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Account::class, parentColumns = ["id"], childColumns = ["accountId"], deferred = true),
        ForeignKey(entity = Account::class, parentColumns = ["id"], childColumns = ["destinationId"], deferred = true),
        ForeignKey(entity = Category::class, parentColumns = ["id"], childColumns = ["categoryId"], deferred = true)
    ],
    indices = [Index(value = ["budgetId", "date"]), Index(value = ["budgetId", "payer", "date"]), Index("accountId"), Index("destinationId"), Index("categoryId"), Index(value = ["ruleId", "date"], unique = true)]
)
data class Entry(
    @PrimaryKey override val id: String,
    val budgetId: String,
    val accountId: String,
    val categoryId: String?,
    val type: EntryType,
    val amount: String,
    val currency: String,
    val accountCurrency: String,
    val budgetCurrency: String,
    val date: String,
    val accountAmount: String? = null,
    val budgetAmount: String? = null,
    val accountRate: String? = null,
    val accountRateDate: String? = null,
    val accountRateSource: String? = null,
    val budgetRate: String? = null,
    val budgetRateDate: String? = null,
    val budgetRateSource: String? = null,
    val note: String = "",
    val creator: String? = null,
    val payer: String? = null,
    val destinationId: String? = null,
    val destinationCurrency: String? = null,
    val destinationAmount: String? = null,
    val transferRate: String? = null,
    val ruleId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = createdAt,
    override val deletedAt: Long? = null
) : LedgerRecord

@Serializable
@Entity(tableName = "rules", foreignKeys = [
    ForeignKey(entity = Budget::class, parentColumns = ["id"], childColumns = ["budgetId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Account::class, parentColumns = ["id"], childColumns = ["accountId"], deferred = true),
    ForeignKey(entity = Category::class, parentColumns = ["id"], childColumns = ["categoryId"], deferred = true)
], indices = [Index("budgetId"), Index("accountId"), Index("categoryId")])
data class Rule(
    @PrimaryKey override val id: String,
    val budgetId: String,
    val type: EntryType,
    val accountId: String,
    val categoryId: String,
    val amount: String,
    val currency: String,
    val frequency: Frequency,
    val interval: Int,
    val start: String,
    val end: String? = null,
    val nextIndex: Long = 0,
    val automatic: Boolean = true,
    val active: Boolean = true,
    val remind: Boolean = false,
    val note: String = "",
    val payer: String? = null,
    val creator: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = createdAt,
    override val deletedAt: Long? = null
) : LedgerRecord

@Serializable
@Entity(tableName = "rates", primaryKeys = ["base", "target", "date", "source"])
data class Rate(
    val base: String,
    val target: String,
    val requestedDate: String,
    val date: String,
    val value: String,
    val source: String,
    val fetchedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "rate_checks", primaryKeys = ["base", "target", "requestedDate"])
data class RateCheck(
    val base: String,
    val target: String,
    val requestedDate: String,
    val rateDate: String? = null,
    val source: String? = null,
    val checkedAt: Long,
    val retryable: Boolean = false
)

@Entity(tableName = "rate_sync")
data class RateSyncRecord(
    @PrimaryKey val key: Int = 0,
    val lastSuccess: Long? = null,
    val successDate: String? = null,
    val lastAttempt: Long? = null,
    val incomplete: Boolean = false,
    val retryable: Boolean = false
)

@Serializable
@Entity(tableName = "members", primaryKeys = ["budgetId", "userId"], foreignKeys = [
    ForeignKey(entity = Budget::class, parentColumns = ["id"], childColumns = ["budgetId"], onDelete = ForeignKey.CASCADE)
])
data class Member(val budgetId: String, val userId: String, val name: String, val role: Role, val active: Boolean = true)

@Entity(tableName = "settings")
data class Preferences(
    @PrimaryKey val key: Int = 0,
    val activeBudget: String? = null,
    val lastAccount: String? = null,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val defaultCurrency: String = "RSD",
    @androidx.room.ColumnInfo(defaultValue = "1") val automaticRates: Boolean = true
)

@Serializable
@Entity(tableName = "outbox", primaryKeys = ["kind", "recordId"], indices = [Index("budgetId")])
data class PendingChange(
    val kind: String,
    val recordId: String,
    val budgetId: String,
    val operationId: String,
    val body: String,
    val baseVersion: Long,
    val remoteVersion: Long? = null,
    val remoteBody: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val rateOnly: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "0") val imported: Boolean = false
)

@Entity(tableName = "import_links", primaryKeys = ["budgetId", "sourceBudgetId", "kind", "sourceId"], foreignKeys = [
    ForeignKey(entity = Budget::class, parentColumns = ["id"], childColumns = ["budgetId"], onDelete = ForeignKey.CASCADE)
])
data class ImportLink(val budgetId: String, val sourceBudgetId: String, val kind: String, val sourceId: String, val targetId: String)

@Entity(tableName = "remote_versions", primaryKeys = ["kind", "recordId"], indices = [Index("budgetId")])
data class RemoteVersion(val kind: String, val recordId: String, val budgetId: String, val version: Long)
