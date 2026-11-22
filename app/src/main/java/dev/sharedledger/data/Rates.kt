package dev.sharedledger.data

import androidx.room.withTransaction
import dev.sharedledger.domain.decimal
import dev.sharedledger.domain.forCurrency
import dev.sharedledger.domain.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

val ledgerJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

@Serializable
data class RateQuote(val value: String, val date: String, val source: String) {
    fun convert(amount: BigDecimal, target: String) = amount.multiply(value.decimal()).forCurrency(target).storage()
}

data class Conversion(val amount: String, val rate: String, val date: String, val source: String)

data class RateBatch(val incomplete: Boolean = false, val retry: Boolean = false, val downloaded: Boolean = false)

internal const val MANUAL_RATE = "Manual"

@Singleton
class Rates @Inject constructor(private val db: LedgerDatabase, private val providers: List<@JvmSuppressWildcards RateProvider>) {
    private val dao get() = db.ledger()
    private val providerCooldown = ConcurrentHashMap<String, Long>()
    private val locks = Array(32) { Mutex() }
    val providerNames get() = providers.map(RateProvider::name)

    fun observe(base: String, targets: Set<String>, day: LocalDate, allowOlder: Boolean, includeManual: Boolean = true) =
        dao.observeRateWindow(base, targets.toList(), day.minusDays(RATE_LOOKBACK).toString(), day.toString())
            .map { targets.associateWith { target -> cached(base, target, day, allowOlder, includeManual) } }

    suspend fun cached(base: String, target: String, day: LocalDate, allowOlder: Boolean = false,
                       includeManual: Boolean = true): RateQuote? {
        if (base == target) return RateQuote("1", day.toString(), "Same currency")
        val end = day.toString()
        val start = day.minusDays(RATE_LOOKBACK).toString()
        val direct = dao.rateWindow(base, target, start, end).filter { includeManual || it.source != MANUAL_RATE }
        val inverse = dao.rateWindow(target, base, start, end).filter { includeManual || it.source != MANUAL_RATE }
        fun Rate.quote(invert: Boolean): RateQuote = if (invert) {
            RateQuote(BigDecimal.ONE.divide(value.decimal(), MathContext.DECIMAL128).storage(), date, "$source (inverse)")
        } else RateQuote(value, date, source)

        direct.find { it.date == end && it.source == MANUAL_RATE }?.let { return it.quote(false) }
        inverse.find { it.date == end && it.source == MANUAL_RATE }?.let { return it.quote(true) }
        val exactDirect = direct.firstOrNull { it.date == end }
        val exactInverse = inverse.firstOrNull { it.date == end }
        exactDirect?.let { return it.quote(false) }
        exactInverse?.let { return it.quote(true) }

        fun confirmed(rows: List<Rate>, check: RateCheck?): Rate? =
            check?.let { receipt -> rows.firstOrNull { it.date == receipt.rateDate && it.source == receipt.source } }
        val directChecked = confirmed(direct, dao.rateCheck(base, target, end))
        val inverseChecked = confirmed(inverse, dao.rateCheck(target, base, end))
        val directQuote = if (allowOlder) direct.firstOrNull { it.source != MANUAL_RATE } else directChecked
        val inverseQuote = if (allowOlder) inverse.firstOrNull { it.source != MANUAL_RATE } else inverseChecked
        return if (inverseQuote != null && (directQuote == null || inverseQuote.date > directQuote.date)) inverseQuote.quote(true)
            else directQuote?.quote(false)
    }

    suspend fun convert(amount: BigDecimal, base: String, target: String, day: LocalDate, allowOlder: Boolean = false): Conversion? =
        cached(base, target, day, allowOlder)?.let { Conversion(it.convert(amount, target), it.value, it.date, it.source) }

    suspend fun hasDueCheck(base: String, targets: Set<String>, day: LocalDate, force: Boolean): Boolean =
        force || targets.any { isDue(dao.rateCheck(base, it, day.toString()), System.currentTimeMillis()) }

    suspend fun hasTransientCheck(base: String, targets: Set<String>, day: LocalDate): Boolean =
        targets.any { dao.rateCheck(base, it, day.toString())?.retryable == true }

    private fun isDue(check: RateCheck?, now: Long): Boolean {
        if (check == null) return true
        if (check.rateDate != null && !check.retryable) return false
        val elapsed = now - check.checkedAt
        val delay = if (check.retryable) 25_000 else 6 * 60 * 60 * 1000L
        return elapsed < 0 || elapsed >= delay
    }

    /** Today uses a dated batch too, so a provider cannot return a quote after the selected day. */
    suspend fun ensure(
        base: String, targets: Set<String>, day: LocalDate, today: LocalDate, force: Boolean = false, scheduled: Boolean = false,
        includeManual: Boolean = true
    ): RateBatch {
        if (day > today) return RateBatch(incomplete = true)
        val lock = locks[Math.floorMod(base.hashCode() + day.hashCode(), locks.size)]
        return lock.withLock {
            val requested = day.toString()
            val now = System.currentTimeMillis()
            val checks = targets.filterNot { it == base }.associateWith { dao.rateCheck(base, it, requested) }
            val missing = checks.filter { (target, check) ->
                when {
                    force -> true
                    !scheduled && cached(base, target, day, includeManual = includeManual) != null -> false
                    else -> isDue(check, now)
                }
            }.keys.toMutableSet()
            val suppressed = checks.filter { (target, check) ->
                check != null && (check.rateDate == null || check.retryable) && target !in missing &&
                    (scheduled || cached(base, target, day, includeManual = includeManual) == null)
            }
            val temporary = mutableSetOf<String>()
            var downloaded = false
            for (provider in providers) {
                if (missing.isEmpty()) break
                if ((providerCooldown[provider.name] ?: 0L) > System.currentTimeMillis()) {
                    temporary.addAll(missing)
                    continue
                }
                val result = try {
                    provider.lookup(base, missing.toSet(), day)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is RateLimited) providerCooldown[provider.name] = System.currentTimeMillis() + e.retryDelayMillis
                    temporary.addAll(missing)
                    continue
                }
                val valid = result.filter { it.base == base && it.target in missing && it.date in day.minusDays(RATE_LOOKBACK).toString()..requested &&
                    it.value.toBigDecimalOrNull()?.signum() == 1 }
                val newest = valid.groupBy(Rate::target).mapValues { (_, rows) -> rows.maxBy(Rate::date) }
                if (newest.isNotEmpty()) {
                    db.withTransaction {
                        dao.putRates(valid)
                        newest.values.forEach { rate ->
                            dao.put(RateCheck(base, rate.target, requested, rate.date, rate.source, now))
                        }
                    }
                    downloaded = true
                }
                missing.removeAll(newest.keys)
            }
            if (missing.isNotEmpty()) db.withTransaction {
                missing.forEach { target ->
                    // A failed manual refresh can continue using the last confirmed observation.
                    val old = checks[target]
                    dao.put(RateCheck(base, target, requested, old?.rateDate, old?.source, now, target in temporary))
                }
            }
            RateBatch(incomplete = missing.isNotEmpty() || suppressed.isNotEmpty(),
                retry = missing.any { it in temporary } || suppressed.values.any { it?.retryable == true }, downloaded = downloaded)
        }
    }
}
