package dev.sharedledger.data

import androidx.room.TypeConverter
import dev.sharedledger.domain.EntryType
import dev.sharedledger.domain.decimal
import dev.sharedledger.domain.storage
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate

@Serializable
enum class BankSource {
    BANK_ACTUAL, BANK_ESTIMATED, BANK_STALE, REFERENCE_API, CACHED_RATE, MANUAL_RATE, ENTERED_AMOUNT, PENDING
}

@Serializable
data class BankConversion(
    val source: BankSource,
    val reference: RateQuote? = null,
    val adjustment: String? = null,
    val confirmedAt: Long? = null,
    val learningEligible: Boolean = true,
    val priorBankConfirmationAt: Long? = null
)

class BankConversionConverter {
    @TypeConverter fun encode(value: BankConversion?): String? = value?.let { ledgerJson.encodeToString(BankConversion.serializer(), it) }
    @TypeConverter fun decode(value: String?): BankConversion? = value?.let { ledgerJson.decodeFromString(BankConversion.serializer(), it) }
}

data class BankPair(
    val budget: String, val account: String, val currency: String, val accountCurrency: String, val direction: EntryType
)

data class BankObservation(val day: LocalDate, val rate: BigDecimal, val confirmedAt: Long)
data class BankOffer(val quote: RateQuote, val conversion: BankConversion) {
    fun amount(original: BigDecimal, target: String) = quote.convert(original, target).decimal().takeIf { it.signum() > 0 }
}

private val precision = MathContext.DECIMAL128

/** Both directions use the same units as Entry.accountRate: account currency per original unit. */
fun effectiveBankRate(original: BigDecimal, account: BigDecimal): BigDecimal {
    require(original.signum() > 0 && account.signum() > 0) { "Enter positive transaction and account amounts." }
    return account.divide(original, precision)
}

fun inverseBankRate(rate: BigDecimal): BigDecimal {
    require(rate.signum() > 0)
    return BigDecimal.ONE.divide(rate, precision)
}

private val bankRecency = compareBy<BankObservation> { it.day }.thenBy { it.confirmedAt }

fun lastBankObservation(observations: List<BankObservation>, day: LocalDate): BankObservation? =
    observations.asSequence().filter { it.day <= day }.maxWithOrNull(bankRecency)

fun referenceBankOffer(quote: RateQuote, day: LocalDate, cached: Boolean): BankOffer {
    val source = when {
        quote.source.startsWith(MANUAL_RATE) -> BankSource.MANUAL_RATE
        cached || quote.date != day.toString() -> BankSource.CACHED_RATE
        else -> BankSource.REFERENCE_API
    }
    return BankOffer(quote, BankConversion(source, quote.takeUnless { source == BankSource.MANUAL_RATE }))
}

class BankRates(private val dao: LedgerDao, private val rates: Rates) {
    suspend fun observations(pair: BankPair, day: LocalDate, except: String? = null): List<BankObservation> =
        dao.bankEntries(pair.budget, pair.account, pair.currency, pair.accountCurrency, pair.direction, day.toString())
            .mapNotNull { row ->
                val metadata = row.bankConversion ?: return@mapNotNull null
                val confirmation = metadata.confirmedAt ?: return@mapNotNull null
                if (row.id == except || metadata.source != BankSource.BANK_ACTUAL || !metadata.learningEligible) return@mapNotNull null
                val rate = row.accountRate?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
                BankObservation(LocalDate.parse(row.date), rate, confirmation)
            }.sortedWith(bankRecency.reversed())

    suspend fun offer(pair: BankPair, day: LocalDate, except: String? = null, offline: Boolean = false): BankOffer? {
        val observation = lastBankObservation(observations(pair, day, except), day)
        if (observation != null) {
            return BankOffer(RateQuote(observation.rate.storage(), observation.day.toString(), "Last bank rate"),
                BankConversion(BankSource.BANK_STALE, priorBankConfirmationAt = observation.confirmedAt))
        }
        rates.cached(pair.currency, pair.accountCurrency, day)?.let { return referenceBankOffer(it, day, offline) }
        return rates.cached(pair.currency, pair.accountCurrency, day, allowOlder = true)?.let { referenceBankOffer(it, day, true) }
    }
}

internal fun Entry.validateBankConversion() {
    val bank = bankConversion ?: return
    require(type != EntryType.TRANSFER && currency != accountCurrency) { "Bank conversion requires two currencies." }
    bank.reference?.let { quote ->
        require(quote.value.toBigDecimalOrNull()?.signum() == 1 && quote.source.length in 1..120 &&
            LocalDate.parse(quote.date) <= LocalDate.parse(date)) { "Invalid bank reference rate." }
    }
    require(bank.adjustment == null || bank.reference != null && bank.adjustment.toBigDecimalOrNull()?.signum() == 1) {
        "Invalid bank adjustment."
    }
    require(if (bank.source == BankSource.PENDING) accountAmount == null else accountAmount?.toBigDecimalOrNull()?.signum() == 1) {
        "Invalid bank account amount."
    }
    require((bank.source == BankSource.BANK_ACTUAL) == (bank.confirmedAt != null)) { "Invalid bank confirmation." }
    if (bank.source == BankSource.BANK_ACTUAL) {
        require(bank.confirmedAt!! > 0 && accountRate?.toBigDecimalOrNull()?.compareTo(
            effectiveBankRate(amount.decimal(), requireNotNull(accountAmount).decimal())) == 0) {
            "Confirmed bank rate does not match the two amounts."
        }
    }
}
