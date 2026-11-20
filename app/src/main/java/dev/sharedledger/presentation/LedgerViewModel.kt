package dev.sharedledger.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.sharedledger.data.*
import dev.sharedledger.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.math.BigDecimal
import javax.inject.Inject
import dev.sharedledger.sync.CloudAuth
import dev.sharedledger.sync.SyncEngine
import dev.sharedledger.sync.RateNetwork

data class LedgerState(
    val budgets: List<Budget> = emptyList(), val budget: Budget? = null, val ledger: Ledger = Ledger(),
    val preferences: Preferences = Preferences(), val period: Period = Period(), val filters: Filters = Filters(),
    val entries: List<Entry> = emptyList(), val comparison: List<Entry> = emptyList(), val totals: Totals = Totals()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LedgerViewModel @Inject constructor(val repository: LedgerRepository, val auth: CloudAuth, val sync: SyncEngine, val recurring: Recurring, val portability: Portability,
    val rates: Rates, val rateRefresh: RateRefresh, val rateNetwork: RateNetwork) : ViewModel() {
    private val events = Channel<String>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()
    val busy = MutableStateFlow(false)
    val period = MutableStateFlow(Period())
    val filters = MutableStateFlow(Filters())
    private val options = combine(repository.principal.flatMapLatest(repository.dao::budgets), repository.dao.preferencesFlow()) { budgets, preferences ->
        val prefs = preferences ?: Preferences()
        Triple(budgets, budgets.find { it.id == prefs.activeBudget } ?: budgets.firstOrNull(), prefs)
    }
    private val selection = options.distinctUntilChangedBy { it.second?.id }.flatMapLatest { (_, budget, _) ->
        if (budget == null) flowOf(null to Ledger()) else repository.observe(budget.id).map { budget.id to it }
    }

    val state = combine(options, selection, period, filters, repository.principal) { (budgets, budget, prefs), (ledgerId, ledger), range, filter, principal ->
        val visible = budget?.takeIf { it.principal == null || it.principal == principal }
        val data = ledger.takeIf { visible != null && ledgerId == visible.id } ?: Ledger()
        val currency = visible?.currency ?: prefs.defaultCurrency
        val names = data.categories.associate { it.id to it.name }
        val matching = filterEntries(data.entries, range.span, filter, names, currency)
        val previous = if (range.mode == PeriodMode.ALL) emptyList() else filterEntries(data.entries, range.shift(-1).span, filter, names, currency)
        LedgerState(budgets.filter { it.principal == null || it.principal == principal },
            visible, data, prefs, range, filter, matching, previous, totals(matching, currency))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, LedgerState())

    init { execute { repository.initialize() } }

    fun select(id: String) = execute {
        filters.value = Filters()
        repository.preferences { it.copy(activeBudget = id) }
    }

    fun execute(success: String? = null, finished: (() -> Unit)? = null, action: suspend () -> Unit) {
        viewModelScope.launch {
            if (busy.value) return@launch
            busy.value = true
            try {
                withContext(Dispatchers.IO) { action() }
                success?.let { events.send(it) }
                finished?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                events.send(e.message ?: "Check your selections and try again.")
            } catch (e: Exception) {
                android.util.Log.w("SharedLedger", "Operation failed (${e.javaClass.simpleName})")
                events.send("The change could not be saved. Please try again.")
            } finally {
                busy.value = false
            }
        }
    }

    fun notice(message: String) { events.trySend(message) }
}
