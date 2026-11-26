package dev.sharedledger.domain

private val digits = Regex("[0-9]+")
private val grouping = Regex("[,. ]")
private val canonicalAmount = Regex("[+-]?[0-9]*(?:\\.[0-9]*)?")

/** Accepts decimal input and unambiguous grouping in pasted numbers. */
internal fun normalizeAmountInput(input: String): String? =
    normalizeDecimal(input, editing = false)

internal fun canonicalAmountEdit(input: String): String? =
    normalizeDecimal(input, editing = true)

internal fun isCanonicalAmountEdit(text: String): Boolean =
    text.length <= 120 && canonicalAmount.matches(text)

private fun normalizeDecimal(input: String, editing: Boolean): String? {
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
    if (editing && unsigned.all { it in '0'..'9' }) return sign + unsigned
    if (editing && (unsigned == "." || unsigned == ",")) return "$sign."
    val commas = unsigned.count { it == ',' }
    val dots = unsigned.count { it == '.' }
    // A pasted value in our display format is grouped. A comma entered by itself is decimal.
    val pastedGrouped = editing && dots == 0 && commas == 1 &&
        unsigned.substringBefore(',').length in 1..3 && unsigned.substringAfter(',').length == 3 &&
        unsigned.all { it in '0'..'9' || it == ',' }
    val decimal = when {
        commas > 0 && dots > 0 -> unsigned.indexOfLast { it == ',' || it == '.' }
        commas + dots == 1 && !pastedGrouped -> unsigned.indexOfFirst { it == ',' || it == '.' }
        else -> -1
    }
    val integer = if (decimal == -1) unsigned else unsigned.substring(0, decimal)
    val fraction = if (decimal == -1) "" else unsigned.substring(decimal + 1)
    if (fraction.isNotEmpty() && !digits.matches(fraction)) return null
    val whole = if (integer.isEmpty() && fraction.isNotEmpty()) {
        if (editing) "" else "0"
    } else if (digits.matches(integer)) integer else {
        val parts = integer.split(grouping)
        val separators = integer.filter { it == ',' || it == '.' || it == ' ' }
        if (parts.size < 2 || separators.toSet().size != 1 ||
            (decimal != -1 && unsigned[decimal] in separators) ||
            parts.first().length !in 1..3 || parts.drop(1).any { it.length != 3 } ||
            parts.any { !digits.matches(it) }) return null
        parts.joinToString("")
    }
    return sign + whole + if (decimal >= 0 && (editing || fraction.isNotEmpty())) ".$fraction" else ""
}

internal fun parseCurrencyInput(input: String) = normalizeAmountInput(input)?.let(::parseMoney)

internal fun amountGroupingOffsets(text: CharSequence): IntProgression {
    val integerEnd = text.indexOf('.').takeIf { it >= 0 } ?: text.length
    val signLength = if (text.firstOrNull() == '-' || text.firstOrNull() == '+') 1 else 0
    return (integerEnd - 3) downTo (signLength + 1) step 3
}

internal fun groupAmount(canonical: String): String = buildString {
    append(canonical)
    for (offset in amountGroupingOffsets(canonical)) insert(offset, ',')
}
