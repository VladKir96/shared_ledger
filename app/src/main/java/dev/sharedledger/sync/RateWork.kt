package dev.sharedledger.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.*
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RateNetwork @Inject constructor(@ApplicationContext context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    fun available(): Boolean = manager.getNetworkCapabilities(manager.activeNetwork)?.let {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } == true

    fun observe() = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(available())
            }
            override fun onLost(network: Network) { trySend(available()) }
        }
        manager.registerDefaultNetworkCallback(callback)
        trySend(available())
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}

class RateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, WorkerDependencies::class.java)
        return try {
            deps.auth().restore()
            val manual = inputData.getBoolean(RateWork.MANUAL, false)
            if (deps.rateRefresh().run(deps.rateNetwork().available(), force = manual && runAttemptCount == 0, manual = manual)) Result.retry()
            else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

object RateWork {
    internal const val MANUAL = "manual"
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun periodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<RateWorker>(6, TimeUnit.HOURS)
            .setConstraints(network).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("ledger-rates-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun enqueue(context: Context, manual: Boolean = false, followUp: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<RateWorker>().setConstraints(network)
            .setInputData(workDataOf(MANUAL to manual))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            if (manual) "ledger-rates-manual" else "ledger-rates",
            if (followUp) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request
        )
    }
}
