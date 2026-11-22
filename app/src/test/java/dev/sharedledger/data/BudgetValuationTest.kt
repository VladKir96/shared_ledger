package dev.sharedledger.data

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class BudgetValuationTest {
    private val day = LocalDate.of(2026, 11, 22)

    @Test fun rateAndAmountProduceTheSameValuation() {
        val original = BigDecimal("100")
        val rate = BudgetValuationEdit.Rate(checkNotNull(parseValuationRate("114,00")))
        val amount = BudgetValuationEdit.Amount(BigDecimal("11400"))
        val valuation = rate.manualConversion(original, "RSD", day)
        assertEquals(Conversion("11400", "114", day.toString(), USER_DEFINED_BUDGET_RATE), valuation)
        assertEquals(valuation, amount.manualConversion(original, "RSD", day))
        assertEquals("22800", rate.manualConversion(BigDecimal("200"), "RSD", day)?.amount)
    }

    @Test fun enteredAmountStaysExactAndDerivedRateHasEnoughPrecision() {
        val amount = BudgetValuationEdit.Amount(BigDecimal("1.01"))
        val conversion = checkNotNull(amount.manualConversion(BigDecimal("3"), "RSD", day))
        assertEquals("1.01", conversion.amount)
        val derived = checkNotNull(parseValuationRate(conversion.rate))
        assertEquals("2.02", BudgetValuationEdit.Rate(derived).manualConversion(BigDecimal("6"), "RSD", day)?.amount)
        assertEquals("0.02", BudgetValuationEdit.Rate(BigDecimal("0.025")).manualConversion(BigDecimal.ONE, "RSD", day)?.amount)
    }

    @Test fun rejectsInvalidOrOutOfRangeValues() {
        for (text in listOf("0", "-114", "1,2.3", "1e9", "x", "9".repeat(80))) assertNull(parseValuationRate(text))
        assertNull(BudgetValuationEdit.Amount(BigDecimal("12.345")).manualConversion(BigDecimal("100"), "RSD", day))
        assertNull(BudgetValuationEdit.Rate(BigDecimal("0.001")).manualConversion(BigDecimal.ONE, "RSD", day))
        assertNull(BudgetValuationEdit.Rate(BigDecimal("100")).manualConversion(BigDecimal("9999999999999999"), "USD", day))
        assertEquals("9999999999999999", BudgetValuationEdit.Rate(BigDecimal.ONE)
            .manualConversion(BigDecimal("9999999999999999"), "USD", day)?.amount)
    }
}
