package dev.sharedledger.domain

private val digits = Regex("[0-9]+")
private val grouping = Regex("[,. ]")

/** Accepts decimal input and unambiguous grouping in pasted numbers. */
internal fun normalizeAmountInput(input: String): String? {
    if (input.length > 120) return null
    val text = input.trim().map { char ->
        when (char) {
            '−' -> '-'
            '\u00a0', '\u202f', '\u2009' -> ' '
            else -> char.digitToIntOrNull()?.digitToChar() ?: char
        }
    }.joinToString("")
    val sign = text.take(1).takeIf { it == "-" || it == "+" }.orEmpty()
    val unsigned = text.drop(sign.length)
    val commas = unsigned.count { it == ',' }
    val dots = unsigned.count { it == '.' }
    val decimal = when {
        commas > 0 && dots > 0 -> unsigned.indexOfLast { it == ',' || it == '.' }
        commas + dots == 1 -> unsigned.indexOfFirst { it == ',' || it == '.' }
        else -> -1
    }
    val integer = if (decimal == -1) unsigned else unsigned.substring(0, decimal)
    val fraction = if (decimal == -1) "" else unsigned.substring(decimal + 1)
    if (fraction.isNotEmpty() && !digits.matches(fraction)) return null
    val whole = if (integer.isEmpty() && fraction.isNotEmpty()) "0" else if (digits.matches(integer)) integer else {
        val parts = integer.split(grouping)
        val separators = integer.filter { it == ',' || it == '.' || it == ' ' }
        if (parts.size < 2 || separators.toSet().size != 1 ||
            (decimal != -1 && unsigned[decimal] in separators) ||
            parts.first().length !in 1..3 || parts.drop(1).any { it.length != 3 } ||
            parts.any { !digits.matches(it) }) return null
        parts.joinToString("")
    }
    return sign + whole + if (fraction.isEmpty()) "" else ".$fraction"
}

internal fun parseCurrencyInput(input: String) = normalizeAmountInput(input)?.let(::parseMoney)
