package dev.sharedledger.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountInputTest {
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
