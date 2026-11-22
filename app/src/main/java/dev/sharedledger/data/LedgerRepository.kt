package dev.sharedledger.data

import androidx.room.withTransaction
import dev.sharedledger.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class Ledger(
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val entries: List<Entry> = emptyList(),
    val rules: List<Rule> = emptyList(),
    val members: List<Member> = emptyList()
)

data class EntryDraft(
    val type: EntryType, val amount: BigDecimal, val date: LocalDate, val accountId: String,
    val categoryId: String?, val currency: String, val note: String = "", val payer: String? = null,
    val destinationId: String? = null, val destinationAmount: BigDecimal? = null,
    val accountRate: BigDecimal? = null, val budgetRate: BigDecimal? = null,
    val bankAmount: BigDecimal? = null, val bankConfirmed: Boolean = false
)

@Singleton
class LedgerRepository @Inject constructor(val db: LedgerDatabase, private val rates: Rates) {
    val dao = db.ledger()
    val bankRates = BankRates(dao, rates)
    val principal = MutableStateFlow<String?>(null)

    fun observe(budget: String): Flow<Ledger> = combine(
        dao.accountsFlow(budget), dao.categoriesFlow(budget), dao.entriesFlow(budget), dao.rulesFlow(budget), dao.members(budget)
    ) { accounts, categories, entries, rules, members ->
        Ledger(accounts.filter { it.deletedAt == null }, categories.filter { it.deletedAt == null }, entries, rules, members)
    }.flowOn(Dispatchers.IO)

    suspend fun purgePrincipal(user: String) = db.withTransaction {
        dao.protectedBudgets(user).forEach { purgeBudget(it.id) }
        dao.removePrincipalChanges(user)
        dao.removeSyncCheckpoint(user)
    }

    suspend fun clearLocalData() = db.withTransaction {
        dao.localBudgets().forEach { purgeBudget(it.id) }
        dao.clearRates()
        dao.clearRateChecks()
        dao.clearRateSync()
        dao.put((dao.preferences() ?: Preferences()).copy(activeBudget = null, lastAccount = null))
    }

    internal suspend fun purgeBudget(budget: String, retainPending: Boolean = false) {
        if (!retainPending) dao.removeBudgetChanges(budget)
        dao.removeVersions(budget)
        dao.purge(budget)
    }

    suspend fun initialize() = db.withTransaction {
        if (dao.preferences() == null) {
            val budget = Budget(id(), "Personal", "RSD")
            addBudget(budget)
            dao.put(Preferences(activeBudget = budget.id))
        }
    }

    suspend fun preferences(change: (Preferences) -> Preferences) = db.withTransaction {
        dao.put(change(dao.preferences() ?: Preferences()))
    }

    suspend fun createBudget(name: String, currency: String, type: BudgetType, icon: String, color: Long, firstAccount: String) =
        db.withTransaction {
            val user = principal.value
            require(type == BudgetType.PERSONAL || user != null) { "Sign in to create a shared budget." }
            val budget = Budget(id(), name.cleanName(), currency.validCurrency(), type, icon, color,
                principal = user.takeIf { type == BudgetType.SHARED }, owner = user.takeIf { type == BudgetType.SHARED })
            addBudget(budget, firstAccount.takeIf { it.isNotBlank() }?.cleanName())
            dao.put((dao.preferences() ?: Preferences()).copy(activeBudget = budget.id))
        }

    private suspend fun addBudget(budget: Budget, firstAccount: String? = "Cash") {
        store(budget, budget)
        firstAccount?.let { store(budget, Account(id(), budget.id, it, budget.currency)) }
        startingCategories(budget.id).forEach { store(budget, it) }
    }

    suspend fun promote(budgetId: String) = db.withTransaction {
        val budget = writable(budgetId, true)
        val user = requireNotNull(principal.value) { "Sign in to sync this budget." }
        require(budget.principal == null) { "This budget is already connected." }
        val cloud = budget.copy(principal = user, owner = user, updatedAt = System.currentTimeMillis())
        store(cloud, cloud)
        dao.accounts(budgetId).forEach { store(cloud, it) }
        dao.categories(budgetId).forEach { store(cloud, it) }
        dao.rules(budgetId).forEach { store(cloud, it.copy(creator = user, payer = user)) }
        dao.entries(budgetId).forEach { store(cloud, it.copy(creator = user, payer = user)) }
    }

    suspend fun updateBudget(value: Budget) = db.withTransaction {
        val previous = writable(value.id, true)
        val currency = value.currency.validCurrency()
        val updated = previous.copy(name = value.name.cleanName(), color = value.color, icon = value.icon,
            currency = currency, updatedAt = System.currentTimeMillis(), deletedAt = value.deletedAt)
        store(updated, updated)
        if (previous.currency != currency) {
            dao.entries(value.id).filter { it.deletedAt == null && it.type != EntryType.TRANSFER }.forEach { entry ->
                val conversion = rates.convert(entry.amount.decimal(), entry.currency, currency, LocalDate.parse(entry.date), false)
                store(updated, entry.copy(budgetCurrency = currency, budgetAmount = conversion?.amount,
                    budgetRate = conversion?.rate, budgetRateDate = conversion?.date,
                    budgetRateSource = conversion?.source, updatedAt = updated.updatedAt))
            }
        }
    }

    suspend fun saveAccount(value: Account) = db.withTransaction {
        val budget = writable(value.budgetId, true)
        val old = dao.account(value.id)
        require(old == null || old.budgetId == budget.id)
        val currency = value.currency.validCurrency()
        require(old == null || old.currency == currency ||
            dao.entries(budget.id).none { it.accountId == value.id || it.destinationId == value.id }) {
            "Create a new account to use a different currency once this account has transactions."
        }
        val opening = requireNotNull(parseMoney(value.opening)) { "Enter a valid opening balance." }
        store(budget, value.copy(name = value.name.cleanName(), opening = opening.forCurrency(currency).storage(),
            updatedAt = System.currentTimeMillis()))
    }

    suspend fun saveCategory(value: Category) = db.withTransaction {
        val budget = writable(value.budgetId, true)
        require(value.type != EntryType.TRANSFER)
        val previous = dao.category(value.id)
        require(previous == null || previous.budgetId == budget.id && previous.type == value.type)
        store(budget, value.copy(name = value.name.cleanName(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun reorder(categories: List<Category>) = db.withTransaction {
        categories.forEachIndexed { index, value ->
            if (value.order != index) saveCategory(value.copy(order = index))
        }
    }

    suspend fun merge(source: Category, destination: Category, archive: Boolean = true) = db.withTransaction {
        val budget = writable(source.budgetId, true)
        require(source.id != destination.id && destination.budgetId == budget.id && source.type == destination.type)
        require(dao.category(destination.id)?.let { !it.archived && it.deletedAt == null } == true)
        val now = System.currentTimeMillis()
        dao.entries(budget.id).filter { it.categoryId == source.id && it.deletedAt == null }
            .forEach { store(budget, it.copy(categoryId = destination.id, updatedAt = now)) }
        dao.rules(budget.id).filter { it.categoryId == source.id && it.deletedAt == null }
            .forEach { store(budget, it.copy(categoryId = destination.id, updatedAt = now)) }
        if (archive) saveCategory(source.copy(archived = true))
    }

    suspend fun saveEntry(budgetId: String, draft: EntryDraft, existingId: String? = null, allowOlder: Boolean = false) =
        db.withTransaction {
            val budget = writable(budgetId)
            val account = requireNotNull(dao.account(draft.accountId)?.takeIf { it.budgetId == budget.id && it.deletedAt == null }) { "Choose an account." }
            val old = existingId?.let { requireNotNull(dao.entry(it)) { "This transaction is no longer available." } }
            require(old == null || old.budgetId == budget.id && old.deletedAt == null)
            store(budget, prepare(budget, account, draft, old, allowOlder))
            dao.put((dao.preferences() ?: Preferences()).copy(lastAccount = account.id))
        }

    internal suspend fun prepare(budget: Budget, account: Account, draft: EntryDraft, old: Entry? = null,
                                 allowOlder: Boolean = false): Entry {
        val currency = draft.currency.validCurrency()
        val amount = old?.takeIf { it.currency == currency && it.amount.decimal().compareTo(draft.amount) == 0 }
            ?.amount?.decimal() ?: draft.amount.forCurrency(currency)
        require(amount.signum() > 0 && amount.precision() - amount.scale() <= 16) { "Enter a positive amount." }
        require(draft.note.length <= 2000) { "Keep notes under 2,000 characters." }
        require(!account.archived || account.id == old?.accountId) { "Choose an active account." }
        val payer = draft.payer ?: principal.value.takeIf { budget.principal != null }
        require(payer == null || payer == principal.value || payer == old?.payer || dao.member(budget.id, payer) != null) { "Choose a member of this budget." }
        val day = draft.date.toString()
        val accountConversion: Conversion?
        val budgetConversion: Conversion?
        val destination: Account?
        val destinationAmount: BigDecimal?
        val category: String?
        var bankConversion: BankConversion? = null
        if (draft.type == EntryType.TRANSFER) {
            require(currency == account.currency) { "Use the source account's currency for a transfer." }
            destination = requireNotNull(draft.destinationId?.let { dao.account(it) }?.takeIf {
                it.budgetId == budget.id && it.id != account.id && it.deletedAt == null && (!it.archived || it.id == old?.destinationId)
            }) { "Choose another account in this budget." }
            destinationAmount = if (destination.currency == currency) amount else
                requireNotNull(draft.destinationAmount?.forCurrency(destination.currency)?.takeIf { it.signum() > 0 && it.precision() - it.scale() <= 16 }) {
                    "Enter the amount received by the destination account."
                }
            accountConversion = Conversion(amount.storage(), "1", day, "Same currency")
            budgetConversion = null
            category = null
        } else {
            val selected = draft.categoryId?.let { dao.category(it) }
            require(selected != null && selected.budgetId == budget.id && selected.type == draft.type &&
                selected.deletedAt == null && (!selected.archived || old?.categoryId == selected.id)) { "Choose a category." }
            category = selected.id
            destination = null
            destinationAmount = null
            require(listOfNotNull(draft.accountRate, draft.budgetRate).all { it.signum() > 0 }) { "Exchange rates must be positive." }
            val sameAccount = old?.takeIf { it.accountId == account.id && it.currency == currency && it.type == draft.type }
            val sameAmount = old?.currency == currency && old.amount.decimal().compareTo(amount) == 0
            val preserved = sameAccount?.takeIf { sameAmount && it.bankConversion?.source == BankSource.BANK_ACTUAL }
            val entered = draft.bankAmount ?: preserved?.accountAmount?.decimal()
            require(preserved == null || draft.bankConfirmed || preserved.accountAmount?.decimal()?.compareTo(entered) == 0) {
                "Confirm the corrected bank amount before saving."
            }
            val actual = draft.bankConfirmed || preserved != null
            val manual = draft.accountRate.takeIf { entered == null }
            val plan = EntryRatePlan(currency, draft.date, account.currency, budget.currency, old)
            val cache = plan.foreignTargets(manual, draft.budgetRate).associateWith { target ->
                rates.cached(currency, target, draft.date, allowOlder)
            }
            val quotes = plan.quotes(cache, manual, draft.budgetRate)
            val pair = BankPair(budget.id, account.id, currency, account.currency, draft.type)
            val storedAccount = sameAccount?.takeIf { it.date == day && sameAmount && entered == null && manual == null }
            accountConversion = when {
                currency == account.currency -> Conversion(amount.storage(), "1", day, "Same currency")
                entered != null -> {
                    val exact = try {
                        entered.setScale(currencyScale(account.currency), RoundingMode.UNNECESSARY)
                    } catch (_: ArithmeticException) {
                        throw IllegalArgumentException("Use the account currency’s decimal precision.")
                    }
                    require(exact.signum() > 0 && exact.precision() - exact.scale() <= 16) { "Enter a positive account amount." }
                    val rate = effectiveBankRate(amount, exact)
                    bankConversion = if (actual) {
                        val unchanged = sameAccount?.takeIf { it.date == day && sameAmount &&
                            it.accountAmount?.decimal()?.compareTo(exact) == 0 && it.bankConversion?.source == BankSource.BANK_ACTUAL }
                        unchanged?.bankConversion ?: BankConversion(BankSource.BANK_ACTUAL, confirmedAt = System.currentTimeMillis())
                    } else BankConversion(BankSource.ENTERED_AMOUNT)
                    Conversion(exact.storage(), rate.storage(), day, if (actual) "Bank actual" else "Entered account amount")
                }
                storedAccount?.accountAmount != null -> {
                    bankConversion = storedAccount.bankConversion
                    Conversion(storedAccount.accountAmount, requireNotNull(storedAccount.accountRate),
                        requireNotNull(storedAccount.accountRateDate), requireNotNull(storedAccount.accountRateSource))
                }
                manual != null -> {
                    val quote = requireNotNull(quotes.account)
                    val converted = quote.convert(amount, account.currency)
                    if (converted.decimal().signum() > 0) {
                        bankConversion = BankConversion(BankSource.MANUAL_RATE)
                        Conversion(converted, quote.value, quote.date, quote.source)
                    } else {
                        bankConversion = BankConversion(BankSource.PENDING)
                        null
                    }
                }
                else -> {
                    val offer = bankRates.offer(pair, draft.date, old?.id, offline = allowOlder)
                    val estimate = offer?.amount(amount, account.currency)
                    bankConversion = offer?.conversion?.takeIf { estimate != null } ?: BankConversion(BankSource.PENDING)
                    if (offer != null && estimate != null) Conversion(estimate.storage(), offer.quote.value, offer.quote.date, offer.quote.source)
                    else if (sameAccount != null && sameAccount.bankConversion == null) quotes.account?.let {
                        val converted = it.convert(amount, account.currency)
                        converted.takeIf { value -> value.decimal().signum() > 0 }?.let { value ->
                            bankConversion = null
                            Conversion(value, it.value, it.date, it.source)
                        }
                    } else null
                }
            }
            val storedBudget = old?.takeIf { sameAmount && it.date == day && it.budgetCurrency == budget.currency &&
                it.budgetAmount != null && draft.budgetRate == null && (manual == null || budget.currency != account.currency) }
            budgetConversion = if (storedBudget?.budgetAmount != null) Conversion(storedBudget.budgetAmount,
                requireNotNull(storedBudget.budgetRate), requireNotNull(storedBudget.budgetRateDate), requireNotNull(storedBudget.budgetRateSource))
            else quotes.budget?.let { Conversion(it.convert(amount, budget.currency), it.value, it.date, it.source) }
        }
        return Entry(old?.id ?: id(), budget.id, account.id, category, draft.type, amount.storage(), currency, account.currency,
            budget.currency, day, accountConversion?.amount, budgetConversion?.amount, accountConversion?.rate,
            accountConversion?.date, accountConversion?.source, budgetConversion?.rate, budgetConversion?.date,
            budgetConversion?.source, draft.note.trim(), old?.creator ?: principal.value.takeIf { budget.principal != null }, payer,
            destination?.id, destination?.currency, destinationAmount?.storage(),
            destinationAmount?.divide(amount, MathContext.DECIMAL128)?.storage(), old?.ruleId,
            old?.createdAt ?: System.currentTimeMillis(), System.currentTimeMillis(), bankConversion = bankConversion)
    }

    internal suspend fun backfillRates(entryId: String) = db.withTransaction {
        val current = dao.entry(entryId) ?: return@withTransaction
        if (current.deletedAt != null || current.type == EntryType.TRANSFER ||
            current.accountAmount != null && current.budgetAmount != null) return@withTransaction
        val budget = dao.budget(current.budgetId) ?: return@withTransaction
        if (budget.deletedAt != null || budget.role == Role.VIEWER ||
            (budget.principal != null && budget.principal != principal.value) ||
            dao.pending("entry", current.id)?.remoteVersion != null) return@withTransaction
        val day = LocalDate.parse(current.date)
        val plan = EntryRatePlan(current.currency, day, current.accountCurrency, current.budgetCurrency, current)
        val quotes = plan.quotes(plan.foreignTargets().associateWith { rates.cached(current.currency, it, day) }, null, null)
        val offer = if (current.accountAmount == null && current.accountCurrency != current.currency)
            bankRates.offer(BankPair(current.budgetId, current.accountId, current.currency, current.accountCurrency, current.type), day, current.id)
                ?.takeIf { it.amount(current.amount.decimal(), current.accountCurrency) != null } else null
        val preferred = offer?.quote ?: quotes.account.takeIf { current.bankConversion == null }
        val filled = current.completeMissingRates(preferred, quotes.budget)
        val updated = if (offer != null) filled.copy(bankConversion = offer.conversion) else filled
        if (updated != current) store(budget, updated.copy(updatedAt = System.currentTimeMillis()), rateOnly = true)
    }

    suspend fun deleteEntry(entry: Entry) = db.withTransaction {
        val budget = writable(entry.budgetId)
        val current = requireNotNull(dao.entry(entry.id))
        require(current.budgetId == budget.id)
        if (current.deletedAt != null) return@withTransaction
        val now = System.currentTimeMillis()
        store(budget, current.copy(deletedAt = now, updatedAt = now))
    }

    internal suspend fun writable(budgetId: String, ownerOnly: Boolean = false): Budget {
        val budget = requireNotNull(dao.budget(budgetId)) { "This budget is unavailable." }
        require(budget.deletedAt == null && (budget.principal == null || budget.principal == principal.value)) { "This budget is unavailable." }
        require(budget.role == Role.OWNER || (!ownerOnly && budget.role == Role.EDITOR)) { "You have read-only access here." }
        return budget
    }

    internal suspend fun store(budget: Budget, value: LedgerRecord, rateOnly: Boolean = false, imported: Boolean = false) {
        val (kind, body) = when (value) {
            is Budget -> { dao.put(value); "budget" to ledgerJson.encodeToString(value) }
            is Account -> { dao.put(value); "account" to ledgerJson.encodeToString(value) }
            is Category -> { dao.put(value); "category" to ledgerJson.encodeToString(value) }
            is Entry -> { dao.put(value); "entry" to ledgerJson.encodeToString(value) }
            is Rule -> { dao.put(value); "rule" to ledgerJson.encodeToString(value) }
            else -> error("Unknown record")
        }
        if (budget.principal != null) {
            val pending = dao.pending(kind, value.id)
            require(pending?.principal == null || pending.principal == budget.principal) {
                "This record has pending changes from another signed-in account."
            }
            val operation = if (imported) importOperation(kind, body) else id()
            dao.put(PendingChange(kind, value.id, budget.id, operation, body,
                pending?.baseVersion ?: dao.version(kind, value.id) ?: 0,
                pending?.remoteVersion, pending?.remoteBody, rateOnly && pending?.rateOnly != false,
                imported || pending?.imported == true, principal = budget.principal))
        }
    }
}

fun String.cleanName(): String = trim().also { require(it.length in 1..80) { "Use a name between 1 and 80 characters." } }
fun String.validCurrency(): String = also { require(it in currencyCodes) { "Choose a valid currency." } }
