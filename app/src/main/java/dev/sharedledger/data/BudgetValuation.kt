package dev.sharedledger.data

import dev.sharedledger.domain.EntryType
import dev.sharedledger.domain.currencyScale
import dev.sharedledger.domain.decimal
import dev.sharedledger.domain.forCurrency
import dev.sharedledger.domain.storage
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

internal const val USER_DEFINED_BUDGET_RATE = "User-defined"
internal const val LAST_CONFIRMED_BUDGET_RATE = "Last manually confirmed rate"
internal const val CACHED_BUDGET_RATE_PREFIX = "Cached reference · "

internal fun String?.isManualBudgetRate() = this == USER_DEFINED_BUDGET_RATE ||
    this == LAST_CONFIRMED_BUDGET_RATE || this?.startsWith(MANUAL_RATE) == true

data class BudgetRatePair(
    val budgetId: String, val accountId: String, val original: String, val budget: String, val direction: EntryType
) {
    fun recorded(entry: Entry?, day: LocalDate): RateQuote? {
        val saved = entry?.takeIf {
            it.budgetId == budgetId && it.accountId == accountId && it.currency == original && it.budgetCurrency == budget &&
                it.type == direction && it.date == day.toString() && it.budgetAmount != null
        } ?: return null
        return RateQuote(saved.budgetRate ?: return null, saved.budgetRateDate ?: return null, saved.budgetRateSource ?: return null)
    }
}

sealed interface BudgetValuationEdit {
    data class Rate(val value: BigDecimal) : BudgetValuationEdit
    data class Amount(val value: BigDecimal) : BudgetValuationEdit
    data object Reference : BudgetValuationEdit
}

private val ratePattern = Regex("[+]?[0-9]+([.,][0-9]+)?")

fun parseValuationRate(text: String): BigDecimal? = text.trim().takeIf { it.length <= 80 && ratePattern.matches(it) }
    ?.replace(',', '.')?.toBigDecimalOrNull()?.takeIf { it.isValuationRate() }

private fun BigDecimal.isValuationRate() = signum() > 0 && stripTrailingZeros().let {
    it.precision() <= 34 && it.scale() <= 60 && it.precision() - it.scale() <= 24
}

internal fun BigDecimal.isBudgetMagnitude() = signum() > 0 && precision() - scale() <= 16

internal fun BudgetValuationEdit.manualConversion(original: BigDecimal, target: String, day: LocalDate): Conversion? {
    if (!original.isBudgetMagnitude()) return null
    val value = when (this) {
        is BudgetValuationEdit.Rate -> this.value.takeIf { it.isValuationRate() }?.let { original.multiply(it).forCurrency(target) }
        is BudgetValuationEdit.Amount -> runCatching { this.value.setScale(currencyScale(target), RoundingMode.UNNECESSARY) }.getOrNull()
        BudgetValuationEdit.Reference -> return null
    }?.takeIf { it.isBudgetMagnitude() } ?: return null
    val rate = when (this) {
        is BudgetValuationEdit.Rate -> this.value
        else -> value.divide(original, MathContext.DECIMAL128)
    }
    return Conversion(value.storage(), rate.storage(), day.toString(), USER_DEFINED_BUDGET_RATE)
}

internal fun RateQuote.budgetConversion(original: BigDecimal, budget: String): Conversion? =
    convert(original, budget).takeIf { it.decimal().let { amount -> amount.precision() - amount.scale() <= 16 } }
        ?.let { Conversion(it, value, date, source) }
