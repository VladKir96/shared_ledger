package dev.sharedledger.push

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.work.*
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.sharedledger.MainActivity
import dev.sharedledger.R
import dev.sharedledger.data.ledgerJson
import dev.sharedledger.domain.money
import dev.sharedledger.sync.CloudApi
import dev.sharedledger.sync.CloudAuth
import dev.sharedledger.sync.CloudError
import dev.sharedledger.sync.Session
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class PushGlobal(
    val enabled: Boolean = true,
    @SerialName("show_amounts") val showAmounts: Boolean = true,
    @SerialName("lock_screen_details") val lockScreenDetails: Boolean = false
)

@Serializable
data class PushBudget(val expense: Boolean = true, val income: Boolean = true, val edits: Boolean = false, val deletions: Boolean = false)

@Serializable
data class PushSettings(val global: PushGlobal = PushGlobal(), val budgets: Map<String, PushBudget> = emptyMap())

@Serializable
data class PushDetails(
    @SerialName("budget_id") val budgetId: String,
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("entry_exists") val entryExists: Boolean,
    val kind: String,
    val budget: String,
    val category: String?,
    val amount: String?,
    val currency: String,
    @SerialName("pending_conversion") val pendingConversion: Boolean,
    @SerialName("lock_screen_details") val lockScreenDetails: Boolean
)

@Singleton
class PushNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloud: CloudApi,
    private val auth: CloudAuth
) {
    private val prefs = context.getSharedPreferences("ledger.push", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    val configured get() = cloud.configured && FirebaseApp.getApps(context).any { it.name == FirebaseApp.DEFAULT_APP_NAME }
    val permitted get() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val permissionAsked get() = prefs.getBoolean("permissionAsked", false)
    fun recordPermissionRequest() = prefs.edit { putBoolean("permissionAsked", true) }

    fun start() {
        if (!configured) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("ledger-push-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PushWorker>(24, TimeUnit.HOURS).setConstraints(network).build())
        scope.launch {
            auth.restore()
            auth.session.map { it?.userId }.distinctUntilChanged().collect { user ->
                rotateDevice(user)
                enqueue(followUp = true)
            }
        }
    }

    @Synchronized
    private fun rotateDevice(user: String?): String? {
        val oldUser = prefs.getString("owner", null)
        val device = prefs.getString("device", null)
        if (oldUser == user && (user == null || device != null)) return device

        val next = user?.let { UUID.randomUUID().toString() }
        val revoked = prefs.getStringSet("revoked", emptySet()).orEmpty() + listOfNotNull(device)
        prefs.edit(commit = true) {
            putString("owner", user)
            putString("device", next)
            putStringSet("revoked", revoked)
            remove("seen")
        }
        cancelVisible()
        return next
    }

    fun enqueue(followUp: Boolean = false) {
        if (!configured) return
        WorkManager.getInstance(context).enqueueUniqueWork("ledger-push-register",
            if (followUp) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PushWorker>().setConstraints(network).build())
    }

    internal suspend fun reconcile() = lock.withLock {
        if (!configured) return@withLock
        val session = auth.current()
        val device = rotateDevice(session?.userId)
        val revoked = prefs.getStringSet("revoked", emptySet()).orEmpty()
        for (stale in revoked) {
            unbind(stale)
            synchronized(this) {
                prefs.edit(commit = true) { putStringSet("revoked", prefs.getStringSet("revoked", emptySet()).orEmpty() - stale) }
            }
        }
        if (session == null || device == null) return@withLock
        if (!permitted) {
            unbind(device)
            cancelVisible()
            return@withLock
        }
        FirebaseMessaging.getInstance().register().awaitResult()
        val registration = FirebaseInstallations.getInstance().id.awaitResult()
        if (auth.session.value?.userId != session.userId) return@withLock
        request(session, "ledger_push_bind", buildJsonObject {
            put("p_device", device)
            put("p_registration", registration)
        })
    }

    @Synchronized
    internal fun registrationChanged(registration: String) {
        if (prefs.getString("lastRegistration", null) == registration) return
        prefs.edit { putString("lastRegistration", registration) }
        enqueue(followUp = true)
    }

    private suspend fun unbind(device: String) {
        cloud.call("/rest/v1/rpc/ledger_push_unbind", buildJsonObject { put("p_device", device) })
    }

    private suspend fun request(session: Session, name: String, body: JsonObject): JsonElement = try {
        cloud.rpc(name, session.access, body)
    } catch (failure: CloudError) {
        if (failure.status != 401) throw failure
        val renewed = auth.current(forceRefresh = true, expectedUserId = session.userId) ?: throw failure
        cloud.rpc(name, renewed.access, body)
    }

    suspend fun settings(budget: String? = null, field: String? = null, enabled: Boolean = false): PushSettings {
        val session = auth.current() ?: throw CloudError(401, "Sign in first")
        val value = request(session, "ledger_push_settings", buildJsonObject {
            if (budget != null) put("p_budget", budget)
            if (field != null) {
                put("p_field", field)
                put("p_enabled", enabled)
            }
        })
        check(auth.session.value?.userId == session.userId)
        return ledgerJson.decodeFromJsonElement<PushSettings>(value).also {
            if (!it.global.enabled) cancelVisible()
        }
    }

    suspend fun details(event: String, user: String): PushDetails? {
        if (!configured || !isPushEvent(event)) return null
        val session = auth.current(expectedUserId = user) ?: return null
        val device = rotateDevice(session.userId) ?: return null
        val value = request(session, "ledger_push_details", buildJsonObject {
            put("p_event", event)
            put("p_device", device)
        })
        return if (value is JsonObject && auth.session.value?.userId == user) ledgerJson.decodeFromJsonElement(value) else null
    }

    @SuppressLint("MissingPermission")
    internal suspend fun display(event: String) {
        if (!configured || !permitted || event in prefs.getStringSet("seen", emptySet()).orEmpty()) return
        auth.restore()
        val user = auth.session.value?.userId ?: return
        val details = details(event, user) ?: return
        val resources = ContextCompat.getContextForLanguage(context)
        val channel = when (details.kind) {
            "expense" -> "shared-expenses"
            "income" -> "shared-income"
            else -> "shared-changes"
        }
        val title = resources.getString(when (details.kind) {
            "expense" -> R.string.push_new_expense
            "income" -> R.string.push_new_income
            "edit" -> R.string.push_transaction_edited
            else -> R.string.push_transaction_deleted
        })
        manager.createNotificationChannels(listOf(
            NotificationChannel("shared-expenses", resources.getString(R.string.push_expenses), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel("shared-income", resources.getString(R.string.push_income), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel("shared-changes", resources.getString(R.string.push_changes), NotificationManager.IMPORTANCE_DEFAULT)
        ))
        val amount = details.amount?.toBigDecimalOrNull()?.let { runCatching { money(it, details.currency, resources.resources.configuration.locales[0]) }.getOrNull() }
        val amountTitle = if (amount == null) title else resources.getString(R.string.push_amount_title, title, amount)
        val description = details.category?.let { category ->
            val label = when (details.kind) {
                "expense" -> R.string.push_expense_body
                "income" -> R.string.push_income_body
                else -> R.string.push_change_body
            }
            resources.getString(label, category)
        }
        val body = listOfNotNull(description, details.budget,
            resources.getString(R.string.push_pending_conversion).takeIf { details.pendingConversion && amount != null }).joinToString("\n")
        val intent = Intent(context, MainActivity::class.java).setAction(ACTION_PUSH)
            .setData("sharedledger://activity/$event".toUri()).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val generic = resources.getString(R.string.push_generic, details.budget)
        val publicVersion = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_push)
            .setContentTitle(resources.getString(R.string.app_name)).setContentText(generic).build()
        val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_push)
            .setContentTitle(amountTitle).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(if (details.lockScreenDetails) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion).build()
        synchronized(this) {
            if (!permitted || auth.session.value?.userId != user || prefs.getString("owner", null) != user) return
            manager.notify(event, PUSH_ID, notification)
            val seen = prefs.getStringSet("seen", emptySet()).orEmpty()
            prefs.edit { putStringSet("seen", (seen + event).toList().takeLast(512).toSet()) }
        }
    }

    private fun cancelVisible() {
        manager.activeNotifications.filter { it.id == PUSH_ID && it.tag?.let(::isPushEvent) == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    companion object {
        const val ACTION_PUSH = "dev.sharedledger.OPEN_PUSH_EVENT"
        private const val PUSH_ID = 641
        internal val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }
}

internal fun isPushEvent(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { result ->
        if (result.isSuccessful) continuation.resume(result.result)
        else continuation.resumeWithException(result.exception ?: IllegalStateException("Push registration unavailable"))
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PushEntryPoint { fun notifications(): PushNotifications }

// FCM's installation ID API delivers refreshes through onRegistered.
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class LedgerMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        EntryPointAccessors.fromApplication(applicationContext, PushEntryPoint::class.java)
            .notifications().registrationChanged(installationId)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val event = message.data["ledger_event"]?.takeIf(::isPushEvent) ?: return
        val work = OneTimeWorkRequestBuilder<PushWorker>().setInputData(workDataOf("event" to event))
            .setConstraints(PushNotifications.network).apply {
                if (Build.VERSION.SDK_INT >= 31) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }.build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork("ledger-push-$event", ExistingWorkPolicy.KEEP, work)
    }
}

class PushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val push = EntryPointAccessors.fromApplication(applicationContext, PushEntryPoint::class.java).notifications()
        return try {
            val event = inputData.getString("event")
            if (event == null) push.reconcile() else push.display(event)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (runAttemptCount < 5 && (error !is CloudError || error.status == 0 || error.status == 429 || error.status >= 500))
                Result.retry()
            else Result.failure()
        }
    }
}
