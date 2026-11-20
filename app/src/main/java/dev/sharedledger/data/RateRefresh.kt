package dev.sharedledger.data

import dev.sharedledger.domain.initialCurrencies
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RateRefresh @Inject constructor(private val repo: LedgerRepository, private val rates: Rates) {
    private val lock = Mutex()
    private val refreshing = MutableStateFlow(false)
    val running: StateFlow<Boolean> = refreshing
    private val dao get() = repo.dao

    /** Returns true when WorkManager should retry a transient failure or continue a large backlog. */
    suspend fun run(
        online: Boolean, force: Boolean = false, today: LocalDate = LocalDate.now(), manual: Boolean = force
    ): Boolean = lock.withLock {
        val preferences = dao.preferences()
        if (!manual && preferences?.automaticRates == false) return false
        if (!online) {
            applyCachedPending(today)
            return true
        }
        refreshing.value = true
        try {
            val principal = repo.principal.value
            val day = today.toString()
            val currencies = (initialCurrencies + dao.trackedCurrencies(principal).first() + listOfNotNull(preferences?.defaultCurrency))
                .distinct()
            val quoteBatches = currencies.flatMap { base ->
                val targets = (if (base in initialCurrencies) currencies else initialCurrencies).filterNot { it == base }
                targets.chunked(24).map { base to it.toSet() }
            }
            val current = quoteBatches.chunked(3).flatMap { group ->
                coroutineScope {
                    group.map { (base, targets) ->
                        async { rates.ensure(base, targets, today, today, force, scheduled = true) }
                    }.awaitAll()
                }
            }
            val previous = dao.rateSync() ?: RateSyncRecord()
            val complete = current.none { it.incomplete }
            val downloaded = current.any { it.downloaded }
            val now = System.currentTimeMillis()
            val record = previous.copy(
                lastSuccess = if (complete && (previous.successDate != day || previous.incomplete || downloaded || force)) now else previous.lastSuccess,
                successDate = if (complete) day else previous.successDate,
                lastAttempt = if (previous.successDate != day || !complete || downloaded || force) now else previous.lastAttempt,
                incomplete = !complete, retryable = current.any { it.retry }
            )
            if (record != previous) dao.put(record)
            val retryPending = completeHistory(principal, today, force)
            retryPending || record.retryable
        } finally {
            refreshing.value = false
        }
    }

    suspend fun applyCachedPending(today: LocalDate = LocalDate.now()) {
        val cutoff = today.toString()
        dao.unconvertedEntries(repo.principal.value).first().filter { it.date <= cutoff }.forEach { repo.backfillRates(it.id) }
    }

    suspend fun saveManualRate(rate: Rate) {
        dao.put(rate)
        dao.unconvertedEntries(repo.principal.value).first().asSequence()
            .filter { it.date == rate.date && (it.currency == rate.base || it.currency == rate.target) }
            .forEach { repo.backfillRates(it.id) }
    }

    private suspend fun completeHistory(principal: String?, today: LocalDate, force: Boolean): Boolean {
        val pending = dao.unconvertedEntries(principal).first().filter { it.date <= today.toString() }
        val groups = pending.groupBy { it.currency to it.date }
        var remaining = HISTORY_BATCHES_PER_RUN
        var retry = false
        for ((key, entries) in groups) {
            val (base, date) = key
            val day = LocalDate.parse(date)
            val targets = entries.flatMap { it.missingRateTargets() }.toSet()
            val missing = targets.filterTo(mutableSetOf()) { rates.cached(base, it, day) == null }
            if (missing.isNotEmpty()) {
                if (remaining == 0) {
                    retry = true
                    continue
                }
                if (rates.hasDueCheck(base, missing, day, force)) {
                    remaining--
                    retry = rates.ensure(base, missing, day, today, force).retry || retry
                } else if (rates.hasTransientCheck(base, missing, day)) retry = true
            }
            for (entry in entries) repo.backfillRates(entry.id)
        }
        return retry
    }

    private companion object {
        const val HISTORY_BATCHES_PER_RUN = 16
    }
}
