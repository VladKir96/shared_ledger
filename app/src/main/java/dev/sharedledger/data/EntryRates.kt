package dev.sharedledger.data

import dev.sharedledger.domain.decimal
import dev.sharedledger.domain.storage
import java.math.BigDecimal
import java.time.LocalDate

data class EntryRatePlan(
    val base: String,
    val day: LocalDate,
    val account: String,
    val budget: String,
    val previous: Entry? = null
) {
    private val stored = previous?.takeIf { it.currency == base && it.date == day.toString() }

    fun foreignTargets(): Set<String> = buildSet {
        if (account != base && accountStored() == null) add(account)
        if (budget != base && stored?.takeIf { it.budgetCurrency == budget }?.budgetAmount == null) add(budget)
    }

    fun accountQuote(cached: RateQuote?, override: BigDecimal?): RateQuote? = when {
        account == base -> RateQuote("1", day.toString(), "Same currency")
        override != null -> RateQuote(override.storage(), day.toString(), MANUAL_RATE)
        else -> accountStored() ?: cached
    }

    private fun accountStored(): RateQuote? = stored?.takeIf { it.accountCurrency == account && it.accountAmount != null }?.let {
        storedQuote(it.accountRate, it.accountRateDate, it.accountRateSource)
    }

    private fun storedQuote(value: String?, date: String?, source: String?): RateQuote? =
        if (value != null && date != null && source != null) RateQuote(value, date, source) else null
}

internal fun Entry.missingRateTargets(): Set<String> =
    EntryRatePlan(currency, LocalDate.parse(date), accountCurrency, budgetCurrency, this).foreignTargets()

internal fun Entry.completeMissingRates(account: RateQuote?, budget: RateQuote?): Entry {
    val amount = amount.decimal()
    var result = this
    if (accountAmount == null && account != null) {
        result = result.copy(accountAmount = account.convert(amount, accountCurrency), accountRate = account.value,
            accountRateDate = account.date, accountRateSource = account.source)
    }
    if (budgetAmount == null && budget != null) {
        budget.budgetConversion(amount, budgetCurrency)?.let {
            result = result.copy(budgetAmount = it.amount, budgetRate = it.rate, budgetRateDate = it.date, budgetRateSource = it.source)
        }
    }
    return result
}
