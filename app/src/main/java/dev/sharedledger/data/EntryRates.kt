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

    fun foreignTargets(accountOverride: BigDecimal? = null, budgetOverride: BigDecimal? = null): Set<String> = buildSet {
        if (account != base && accountOverride == null && existingAccount() == null) add(account)
        if (budget != base && (budgetOverride ?: accountOverride.takeIf { budget == account }) == null && existingBudget() == null) add(budget)
    }

    fun quotes(cache: Map<String, RateQuote?>, accountOverride: BigDecimal?, budgetOverride: BigDecimal?): EntryQuotes =
        EntryQuotes(
            quote(account, accountOverride, existingAccount(), cache),
            quote(budget, budgetOverride ?: accountOverride.takeIf { budget == account }, existingBudget(), cache)
        )

    private fun existingAccount() = accountStored() ?: budgetStored().takeIf { account == budget }
    private fun existingBudget() = budgetStored() ?: accountStored().takeIf { account == budget }

    private fun accountStored(): RateQuote? = stored?.takeIf { it.accountCurrency == account && it.accountAmount != null }?.let {
        storedQuote(it.accountRate, it.accountRateDate, it.accountRateSource)
    }

    private fun budgetStored(): RateQuote? = stored?.takeIf { it.budgetCurrency == budget && it.budgetAmount != null }?.let {
        storedQuote(it.budgetRate, it.budgetRateDate, it.budgetRateSource)
    }

    private fun quote(target: String, override: BigDecimal?, previous: RateQuote?, cache: Map<String, RateQuote?>): RateQuote? = when {
        target == base -> RateQuote("1", day.toString(), "Same currency")
        override != null -> RateQuote(override.storage(), day.toString(), MANUAL_RATE)
        else -> previous ?: cache[target]
    }

    private fun storedQuote(value: String?, date: String?, source: String?): RateQuote? =
        if (value != null && date != null && source != null) RateQuote(value, date, source) else null
}

data class EntryQuotes(val account: RateQuote?, val budget: RateQuote?)

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
        result = result.copy(budgetAmount = budget.convert(amount, budgetCurrency), budgetRate = budget.value,
            budgetRateDate = budget.date, budgetRateSource = budget.source)
    }
    return result
}
