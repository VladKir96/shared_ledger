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
import dev.sharedledger.sync.AuthLink
import dev.sharedledger.sync.AuthLinkProblem
import dev.sharedledger.sync.CloudAuth
import dev.sharedledger.sync.CloudError
import dev.sharedledger.sync.ConfirmationResult
import dev.sharedledger.sync.EmailPurpose
import dev.sharedledger.sync.JoinedBudget
import dev.sharedledger.sync.SyncEngine
import dev.sharedledger.sync.RateNetwork
import dev.sharedledger.push.PushNotifications
import dev.sharedledger.updates.AppUpdates

data class LedgerState(
    val budgets: List<Budget> = emptyList(), val budget: Budget? = null, val ledger: Ledger = Ledger(),
    val preferences: Preferences = Preferences(), val period: Period = Period(), val filters: Filters = Filters(),
    val entries: List<Entry> = emptyList(), val comparison: List<Entry> = emptyList(), val totals: Totals = Totals()
)

internal data class PushDestination(val budget: String, val user: String, val entry: String?)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LedgerViewModel @Inject constructor(val repository: LedgerRepository, val auth: CloudAuth, val sync: SyncEngine, val recurring: Recurring, val portability: Portability,
    val rates: Rates, val rateRefresh: RateRefresh, val rateNetwork: RateNetwork, val push: PushNotifications, val updates: AppUpdates) : ViewModel() {
    private val events = Channel<String>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()
    val busy = MutableStateFlow(false)
    private val joining = MutableStateFlow(false)
    val joiningInvitation = joining.asStateFlow()
    private val callback = MutableStateFlow<AuthCallback?>(null)
    val authCallback = callback.asStateFlow()
    private val pushEvent = MutableStateFlow<String?>(null)
    val pendingPush = pushEvent.asStateFlow()
    private var activeLink: AuthLink? = null
    private var nextLink: AuthLink? = null
    private var authJob: Job? = null
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

    fun clearOverviewFilters() {
        filters.value = Filters()
    }

    fun select(id: String) = execute {
        clearOverviewFilters()
        repository.preferences { it.copy(activeBudget = id) }
    }

    fun acceptPush(event: String) { pushEvent.value = event }

    fun clearPush(event: String) { pushEvent.compareAndSet(event, null) }

    internal suspend fun openPush(event: String): PushDestination? {
        auth.restore()
        val user = auth.session.value?.userId ?: return null
        push.details(event, user) ?: return null
        sync.synchronize()
        val details = push.details(event, user) ?: return null
        val budget = repository.dao.budget(details.budgetId)?.takeIf { it.type == BudgetType.SHARED && it.principal == user } ?: return null
        if (auth.session.value?.userId != user || pushEvent.value != event) return null
        val entry = if (details.entryExists) repository.dao.entry(details.transactionId)
            ?.takeIf { it.budgetId == budget.id && it.deletedAt == null } else null
        clearOverviewFilters()
        repository.preferences { it.copy(activeBudget = budget.id) }
        return PushDestination(budget.id, user, entry?.id)
    }

    fun acceptInvitation(input: String, failed: (String) -> Unit, finished: (downloaded: Boolean) -> Unit) {
        var joined: JoinedBudget? = null
        execute(failed = failed, finished = {
            val result = checkNotNull(joined)
            notice(when {
                !result.downloaded -> "Membership confirmed. The shared budget will appear after synchronization."
                result.alreadyMember -> "You are already a member of this budget."
                else -> "Budget joined"
            })
            finished(result.downloaded)
        }) {
            joining.value = true
            try {
                joined = sync.join(input)
                filters.value = Filters()
            } finally {
                joining.value = false
            }
        }
    }

    fun execute(
        success: String? = null, finished: (() -> Unit)? = null, failed: ((String) -> Unit)? = null,
        action: suspend () -> Unit
    ) {
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
                val message = e.message ?: "Check your selections and try again."
                if (failed == null) events.send(message) else failed(message)
            } catch (e: Exception) {
                android.util.Log.w("SharedLedger", "Operation failed (${e.javaClass.simpleName})")
                val message = "The change could not be saved. Please try again."
                if (failed == null) events.send(message) else failed(message)
            } finally {
                busy.value = false
            }
        }
    }

    fun acceptAuthLink(link: AuthLink) {
        val active = activeLink
        if (active == link || active is AuthLink.Code && link is AuthLink.Code &&
            active.purpose == link.purpose && active.fingerprint == link.fingerprint) return
        if (authJob?.isActive == true) nextLink = link else showAuthLink(link)
    }

    fun dismissAuthCallback() {
        if (authJob?.isActive == true) return
        callback.value = null
        activeLink = null
    }

    fun retryAuthCallback(link: AuthLink.Code) {
        if (authJob?.isActive != true) showAuthLink(link)
    }

    private fun showAuthLink(link: AuthLink) {
        activeLink = link
        if (link is AuthLink.Invalid) {
            callback.value = AuthCallback.Failed(link.purpose, when (link.problem) {
                AuthLinkProblem.INVALID -> "This email link is invalid. Request a new one in Cloud sync."
                AuthLinkProblem.EXPIRED_OR_USED -> "This email link has expired or has already been used. Request a new one."
                AuthLinkProblem.FAILED -> "Supabase could not verify this email link. Request a new one."
            })
            return
        }
        if (!sync.configured) {
            callback.value = AuthCallback.Failed(link.purpose, "This build uses local mode. Install a cloud-enabled build to open email links.")
            return
        }
        link as AuthLink.Code
        callback.value = AuthCallback.Checking(link.purpose)
        runAuthCallback {
            callback.value = try {
                if (link.purpose == EmailPurpose.CONFIRMATION) {
                    val result = withContext(Dispatchers.IO) { auth.confirmEmail(link) }
                    AuthCallback.Complete(link.purpose, if (result == ConfirmationResult.SIGNED_IN)
                        "Your email is verified and you are signed in."
                    else "Your email is verified. To use the verified account, sign out of the current account and sign in again.")
                } else {
                    withContext(Dispatchers.IO) { auth.authorizeRecovery(link) }
                    AuthCallback.Password(link)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val canRetry = e is CloudError && (e.status == 0 || e.status == 429 || e.status >= 500)
                AuthCallback.Failed(link.purpose, authMessage(e), link.takeIf { canRetry })
            }
        }
    }

    fun updateRecoveredPassword(link: AuthLink.Code, password: String) {
        if (authJob?.isActive == true) return
        val form = callback.value as? AuthCallback.Password ?: return
        if (form.link != link) return
        callback.value = form.copy(saving = true, error = null)
        runAuthCallback {
            callback.value = try {
                withContext(Dispatchers.IO) { auth.finishRecovery(link, password) }
                AuthCallback.Complete(link.purpose, "Your password was updated. Use the new password the next time you sign in.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is CloudError && e.status == 410) AuthCallback.Failed(link.purpose, authMessage(e))
                else form.copy(saving = false, error = authMessage(e))
            }
        }
    }

    private fun runAuthCallback(action: suspend () -> Unit) {
        authJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            action()
            nextLink?.let {
                nextLink = null
                showAuthLink(it)
            }
        }
        authJob?.start()
    }

    private fun authMessage(error: Exception) = (error as? IllegalArgumentException)?.message
        ?: "The email request could not be completed. Please try again."

    fun notice(message: String) { events.trySend(message) }
}
