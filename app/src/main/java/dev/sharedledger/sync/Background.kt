package dev.sharedledger.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.sharedledger.MainActivity
import dev.sharedledger.R
import dev.sharedledger.data.LedgerRepository
import dev.sharedledger.data.Recurring
import dev.sharedledger.data.RateRefresh
import dev.sharedledger.data.missingRateTargets
import dev.sharedledger.data.ledgerJson
import dev.sharedledger.data.nextDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WorkerDependencies {
    fun engine(): SyncEngine
    fun recurring(): Recurring
    fun auth(): CloudAuth
    fun rateRefresh(): RateRefresh
    fun rateNetwork(): RateNetwork
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, WorkerDependencies::class.java)
        return if (deps.engine().synchronize()) Result.success() else Result.retry()
    }
}

class RecurringWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, WorkerDependencies::class.java)
        deps.auth().restore()
        val reminders = deps.recurring().runDue()
        val context = applicationContext
        val canNotify = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (canNotify && reminders.isNotEmpty()) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("ledger-recurring", "Recurring transactions", NotificationManager.IMPORTANCE_DEFAULT))
            val prefs = context.getSharedPreferences("recurrence_reminders", Context.MODE_PRIVATE)
            val launch = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            reminders.forEach { rule ->
                val day = rule.nextDate()?.toString() ?: return@forEach
                if (prefs.getString(rule.id, null) == day) return@forEach
                manager.notify(rule.id.hashCode(), Notification.Builder(context, "ledger-recurring").setSmallIcon(R.drawable.ic_ledger)
                    .setContentTitle("Recurring transaction due").setContentText("Open SharedLedger to review scheduled activity.")
                    .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(launch).setAutoCancel(true).build())
                prefs.edit().putString(rule.id, day).apply()
            }
        }
        if (reminders.isNotEmpty()) Background.enqueueSync(context)
        return Result.success()
    }
}

@Singleton
class Background @Inject constructor(
    @ApplicationContext private val context: Context, private val api: CloudApi,
    private val repository: LedgerRepository, private val auth: CloudAuth,
    private val rateRefresh: RateRefresh
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val manager get() = WorkManager.getInstance(context)

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun start() {
        manager.enqueueUniquePeriodicWork("ledger-recurring-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RecurringWorker>(12, TimeUnit.HOURS).build())
        RateWork.periodic(context)
        scope.launch {
            var previous = emptySet<Triple<String, String, String>>()
            repository.principal.flatMapLatest(repository.dao::unconvertedEntries).map { entries ->
                entries.flatMap { entry ->
                    entry.missingRateTargets().ifEmpty { setOf(entry.currency) }.map { target -> Triple(entry.currency, target, entry.date) }
                }.toSet()
            }.distinctUntilChanged().collect { next ->
                if ((next - previous).isNotEmpty() && repository.dao.preferences()?.automaticRates != false)
                    RateWork.enqueue(context, followUp = true)
                previous = next
            }
        }
        scope.launch {
            combine(
                repository.dao.preferencesFlow(),
                repository.principal.flatMapLatest(repository.dao::trackedCurrencies)
            ) { preferences, currencies ->
                if (preferences?.automaticRates == false) emptySet() else (currencies + listOfNotNull(preferences?.defaultCurrency)).toSet()
            }.distinctUntilChanged().drop(1).filter { it.isNotEmpty() }
                .collect { RateWork.enqueue(context, followUp = true) }
        }
        if (!api.configured) return
        manager.enqueueUniquePeriodicWork("ledger-sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).build())
        scope.launch { auth.restore() }
        scope.launch { signals.debounce(1_000).collect { enqueueSync(context) } }
        scope.launch {
            combine(repository.dao.pendingFlow(), auth.session.map { it?.userId }.distinctUntilChanged()) { list, user ->
                if (user == null) emptySet() else list.filter { it.remoteVersion == null }.mapTo(hashSetOf()) { it.operationId }
            }.distinctUntilChanged().debounce(500).collect { if (it.isNotEmpty()) enqueueSync(context) }
        }
        scope.launch {
            auth.session.distinctUntilChangedBy { it?.access }.collectLatest { session ->
                if (session != null) {
                    enqueueSync(context)
                    live(session)
                }
            }
        }
    }

    fun foreground() {
        scope.launch { rateRefresh.applyCachedPending() }
        RateWork.enqueue(context)
        manager.enqueueUniqueWork("ledger-recurring-immediate", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<RecurringWorker>().build())
        if (api.configured && auth.session.value != null) enqueueSync(context)
    }

    private suspend fun live(session: Session) {
        while (currentCoroutineContext().isActive) {
            val connected = CompletableDeferred<WebSocket>()
            val closed = CompletableDeferred<Unit>()
            val topic = "realtime:public:budgets"
            val socket = api.client.newWebSocket(Request.Builder().url(api.realtimeUrl).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(message(topic, "phx_join", "1", buildJsonObject {
                        put("access_token", session.access)
                        put("config", buildJsonObject {
                            put("postgres_changes", buildJsonArray { add(buildJsonObject {
                                put("event", "*"); put("schema", "public"); put("table", "budgets")
                            }) })
                        })
                    }))
                    connected.complete(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val event = runCatching { ledgerJson.parseToJsonElement(text).jsonObject.string("event") }.getOrNull()
                    if (event in listOf("postgres_changes", "INSERT", "UPDATE", "DELETE")) signals.tryEmit(Unit)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
            })
            try {
                withTimeoutOrNull(25_000) {
                    kotlinx.coroutines.selects.select<Unit> {
                        connected.onAwait { }
                        closed.onAwait { }
                    }
                }
                var ref = 2
                while (connected.isCompleted && !closed.isCompleted) {
                    withTimeoutOrNull(25_000) { closed.await() } ?: socket.send(message("phoenix", "heartbeat", (ref++).toString()))
                }
            } finally {
                socket.cancel()
            }
            delay(15_000)
        }
    }

    companion object {
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun enqueueSync(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("ledger-sync-immediate", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS).build())
        }
        private fun message(topic: String, event: String, ref: String, payload: JsonObject = buildJsonObject {}): String =
            buildJsonObject { put("topic", topic); put("event", event); put("ref", ref); put("payload", payload) }.toString()
    }
}
