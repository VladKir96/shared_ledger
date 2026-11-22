package dev.sharedledger.domain

import dev.sharedledger.data.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

class BankRatesTest {
    private val day = LocalDate.of(2026, 11, 21)

    @Test fun actualAndInverseUseExactDecimalsInBothDirections() {
        val debit = effectiveBankRate(BigDecimal("2500"), BigDecimal("21.50"))
        assertEquals("0.0086", debit.storage())
        assertEquals("116.2791", inverseBankRate(debit).setScale(4, RoundingMode.HALF_EVEN).storage())
        val credit = effectiveBankRate(BigDecimal("100000"), BigDecimal("1050"))
        assertEquals("0.0105", credit.storage())
        assertEquals("95.2381", inverseBankRate(credit).setScale(4, RoundingMode.HALF_EVEN).storage())
    }

    @Test fun lastRateUsesTheLatestApplicableTransactionDateThenConfirmationTime() {
        val oldRate = BankObservation(day.minusYears(2), BigDecimal("0.007"), 150)
        val earlier = BankObservation(day.minusDays(1), BigDecimal("0.0085"), 90)
        val corrected = BankObservation(day.minusDays(1), BigDecimal("0.0086"), 120)
        val future = BankObservation(day.plusDays(1), BigDecimal("0.02"), 200)
        val history = listOf(oldRate, earlier, corrected, future)
        assertEquals(corrected, lastBankObservation(history, day))
        assertEquals(oldRate, lastBankObservation(history, day.minusDays(2)))
        assertNull(lastBankObservation(history, day.minusYears(3)))
        assertEquals(BigDecimal("25.80"), RateQuote(corrected.rate.storage(), day.toString(), "Last bank rate")
            .convert(BigDecimal("3000"), "USD").toBigDecimal().forCurrency("USD"))
    }

    @Test fun referenceFallbackDistinguishesApiCacheAndManualSources() {
        val live = RateQuote("0.0085", day.toString(), "Fixture API")
        assertEquals(BankSource.REFERENCE_API, referenceBankOffer(live, day, false).conversion.source)
        assertEquals(BankSource.CACHED_RATE, referenceBankOffer(live, day, true).conversion.source)
        val older = live.copy(date = day.minusDays(4).toString())
        assertEquals(BankSource.CACHED_RATE, referenceBankOffer(older, day, false).conversion.source)
        assertEquals(older.date, referenceBankOffer(older, day, true).quote.date)
        assertEquals(BankSource.MANUAL_RATE, referenceBankOffer(live.copy(source = "Manual"), day, true).conversion.source)
    }

    @Test fun displayUsesBudgetFirstAndAddsBothOtherCurrenciesOnlyWhenNeeded() {
        val three = Entry("id", "b", "a", "c", EntryType.INCOME, "100000", "RUB", "USD", "RSD", day.toString(),
            accountAmount = "1050", budgetAmount = "117000", budgetRateSource = "Reference")
        val amounts = transactionAmounts(three, "RSD")
        assertEquals(MonetaryLine(BigDecimal("117000"), "RSD", true), amounts.primary)
        assertEquals(listOf("USD", "RUB"), amounts.secondary.map { it.currency })
        assertNull(transactionAmounts(three.copy(budgetAmount = null), "RSD").primary)
        val same = three.copy(type = EntryType.EXPENSE, amount = "350", currency = "RSD", accountCurrency = "RSD",
            accountAmount = "350.00", budgetAmount = "350")
        assertTrue(transactionAmounts(same, "RSD").secondary.isEmpty())
    }

    @Test fun accountBalancesUseActualAmountsAndSeparatelyRecordedFeesOnlyOnce() {
        val account = Account("a", "b", "Debit", "USD", opening = "100")
        val expense = Entry("purchase", "b", "a", "c", EntryType.EXPENSE, "2500", "RSD", "USD", "RSD", day.toString(),
            accountAmount = "21.50", budgetAmount = "2500")
        val fee = expense.copy(id = "fee", amount = "0.50", currency = "USD", accountAmount = "0.50",
            budgetAmount = "58.75", budgetRateSource = "Reference")
        val transfer = expense.copy(id = "transfer", type = EntryType.TRANSFER, currency = "USD", accountAmount = "10")
        val rows = listOf(expense, fee, transfer)
        assertEquals(BigDecimal("68.00"), accountBalance(account, rows).value)
        assertEquals(BigDecimal("2558.75"), totals(rows, "RSD").expenses)
        assertTrue(totals(rows, "RSD").approximateExpenses)
    }
}
