package dev.sharedledger.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountInputTest {
    @Test fun displayedGroupingPreservesPartialInputAndExactDecimals() {
        mapOf(
            "" to "", "100" to "100", "1000" to "1,000", "12500" to "12,500",
            "1500000" to "1,500,000", "1234567.89" to "1,234,567.89",
            "-1500000.00" to "-1,500,000.00", "1000000." to "1,000,000.",
            "1000000000000000.000100" to "1,000,000,000,000,000.000100"
        ).forEach { (raw, formatted) ->
            assertEquals(formatted, groupAmount(raw))
            assertEquals(raw, canonicalAmountEdit(formatted))
        }
        assertEquals(".", canonicalAmountEdit(","))
        assertEquals("32.00", canonicalAmountEdit("32,00"))
        assertEquals("1000", canonicalAmountEdit("1,000"))
        assertEquals("2500.25", canonicalAmountEdit("2\u202f500,25"))
        assertEquals("1234.56", canonicalAmountEdit("1.234,56"))
        assertEquals(".050", canonicalAmountEdit(",050"))
        assertEquals("-.0", canonicalAmountEdit("−,0"))
        assertEquals("1234.50".toBigDecimal(), parseCurrencyInput("1234.50"))
        assertNull(canonicalAmountEdit("1 23,45"))
    }

    @Test fun decimalTypingAndUnambiguousClipboardGrouping() {
        mapOf(
            "114,50" to "114.50", "114.50" to "114.50", ",5" to "0.5", "1," to "1", "1,234" to "1.234",
            "1,234,567.89" to "1234567.89", "1.234.567,89" to "1234567.89", "2\u202f500,25" to "2500.25",
            "1.234.567" to "1234567", "١٢,٥" to "12.5", "−100,02" to "-100.02"
        ).forEach { (input, expected) -> assertEquals(input, expected, normalizeAmountInput(input)) }
    }

    @Test fun rejectsExpressionsAndMalformedGroupingAndRetainsMoneyLimits() {
        listOf("1+2", "1 23,4", "12,12.60", "1,234,56", "1..00", "123 Rs", ",", "1".repeat(121))
            .forEach { assertNull(it, normalizeAmountInput(it)) }
        assertNull(parseCurrencyInput("1.01234567891"))
        assertNull(parseCurrencyInput("10000000000000000"))
        assertEquals("12.500".toBigDecimal(), parseCurrencyInput("12,500"))
    }
}
