package dev.sharedledger.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Currency
import java.util.Locale
import java.util.UUID

val initialCurrencies = listOf("RSD", "EUR", "RUB", "USD", "GBP", "CHF")
val currencyCodes: List<String> by lazy {
    initialCurrencies + Currency.getAvailableCurrencies().map { it.currencyCode }
        .filterNot { it in initialCurrencies }.sorted()
}

enum class EntryType { EXPENSE, INCOME, TRANSFER }
enum class AccountType { CASH, DEBIT_CARD, CREDIT_CARD, BANK, SAVINGS, OTHER }
enum class BudgetType { PERSONAL, SHARED }
enum class Role { OWNER, EDITOR, VIEWER }
enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY, CUSTOM }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

fun id(): String = UUID.randomUUID().toString()
fun occurrenceId(rule: String, day: LocalDate): String =
    UUID.nameUUIDFromBytes("sharedledger:occurrence:$rule:$day".toByteArray(Charsets.UTF_8)).toString()

fun currencyScale(code: String): Int = if (code == "RSD") 2 else Currency.getInstance(code).defaultFractionDigits.coerceIn(0, 6)
fun BigDecimal.forCurrency(code: String): BigDecimal = setScale(currencyScale(code), RoundingMode.HALF_EVEN)
fun String.decimal(): BigDecimal = toBigDecimal()
fun BigDecimal.storage(): String = stripTrailingZeros().toPlainString()
fun parseMoney(text: String): BigDecimal? = text.trim().takeIf { it.length <= 28 }
    ?.takeIf { it.matches(Regex("[+-]?[0-9]+([.,][0-9]+)?")) }
    ?.replace(',', '.')?.toBigDecimalOrNull()
    ?.takeIf { it.precision() - it.scale() <= 16 && it.scale() <= 10 }
object LedgerFormats {
    @Volatile var locale: Locale = Locale.getDefault()
}

fun money(amount: BigDecimal, code: String, locale: Locale = LedgerFormats.locale): String {
    val formatter = NumberFormat.getNumberInstance(locale)
    formatter.minimumFractionDigits = currencyScale(code)
    formatter.maximumFractionDigits = currencyScale(code)
    return "${formatter.format(amount)} $code"
}

enum class PeriodMode { DAY, WEEK, MONTH, THREE_MONTHS, SIX_MONTHS, YEAR, ALL, CUSTOM, SPECIFIC }

data class DateSpan(val from: LocalDate? = null, val to: LocalDate? = null) {
    init { require(from == null || to == null || from <= to) }
    operator fun contains(value: LocalDate): Boolean = (from == null || value >= from) && (to == null || value <= to)
}

data class Period(val mode: PeriodMode = PeriodMode.MONTH, val anchor: LocalDate = LocalDate.now(), val custom: DateSpan = DateSpan()) {
    val span: DateSpan
        get() {
            val first = anchor.withDayOfMonth(1)
            val week = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return when (mode) {
                PeriodMode.DAY, PeriodMode.SPECIFIC -> DateSpan(anchor, anchor)
                PeriodMode.WEEK -> DateSpan(week, week.plusDays(6))
                PeriodMode.MONTH -> DateSpan(first, YearMonth.from(anchor).atEndOfMonth())
                PeriodMode.THREE_MONTHS -> DateSpan(first.minusMonths(2), YearMonth.from(anchor).atEndOfMonth())
                PeriodMode.SIX_MONTHS -> DateSpan(first.minusMonths(5), YearMonth.from(anchor).atEndOfMonth())
                PeriodMode.YEAR -> DateSpan(anchor.withDayOfYear(1), LocalDate.of(anchor.year, 12, 31))
                PeriodMode.ALL -> DateSpan()
                PeriodMode.CUSTOM -> custom
            }
        }

    fun shift(direction: Long): Period = when (mode) {
        PeriodMode.DAY, PeriodMode.SPECIFIC -> copy(anchor = anchor.plusDays(direction))
        PeriodMode.WEEK -> copy(anchor = anchor.plusWeeks(direction))
        PeriodMode.MONTH -> copy(anchor = anchor.plusMonths(direction))
        PeriodMode.THREE_MONTHS -> copy(anchor = anchor.plusMonths(direction * 3))
        PeriodMode.SIX_MONTHS -> copy(anchor = anchor.plusMonths(direction * 6))
        PeriodMode.YEAR -> copy(anchor = anchor.plusYears(direction))
        PeriodMode.ALL -> this
        PeriodMode.CUSTOM -> {
            val start = custom.from
            val end = custom.to
            if (start == null || end == null) this else {
                val days = (ChronoUnit.DAYS.between(start, end) + 1) * direction
                copy(custom = DateSpan(start.plusDays(days), end.plusDays(days)))
            }
        }
    }

}

fun occurrence(start: LocalDate, frequency: Frequency, interval: Long, index: Long): LocalDate {
    require(interval > 0 && index >= 0)
    val offset = Math.multiplyExact(interval, index)
    return when (frequency) {
        Frequency.DAILY, Frequency.CUSTOM -> start.plusDays(offset)
        Frequency.WEEKLY -> start.plusWeeks(offset)
        Frequency.MONTHLY -> start.plusMonths(offset)
        Frequency.YEARLY -> start.plusYears(offset)
    }
}

/** Evaluates a bounded expression with standard multiplication and division precedence. */
fun calculate(expression: String): BigDecimal? {
    if (expression.isBlank() || expression.length > 120) return null
    val compact = expression.replace('×', '*').replace('÷', '/').replace('−', '-').filterNot(Char::isWhitespace)
    val tokens = Regex("[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+|[+*/-]").findAll(compact).map { it.value }.toList()
    if (tokens.joinToString("") != compact) return null
    var values = tokens
    if (values.firstOrNull() == "-" && values.size >= 2) values = listOf("-" + values[1]) + values.drop(2)
    var term = values.firstOrNull()?.toBigDecimalOrNull() ?: return null
    var total = BigDecimal.ZERO
    var sign = 1
    for (i in 1 until values.size step 2) {
        val next = values.getOrNull(i + 1)?.toBigDecimalOrNull() ?: return null
        when (values[i]) {
            "*" -> term = term.multiply(next, MathContext.DECIMAL128)
            "/" -> {
                if (next.signum() == 0) return null
                term = term.divide(next, MathContext.DECIMAL128)
            }
            "+", "-" -> {
                total = total.add(term.multiply(BigDecimal.valueOf(sign.toLong())))
                term = next
                sign = if (values[i] == "+") 1 else -1
            }
            else -> return null
        }
    }
    return total.add(term.multiply(BigDecimal.valueOf(sign.toLong())))
        .takeIf { it.precision() - it.scale() <= 16 }
}
