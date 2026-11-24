package dev.sharedledger.updates

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.sharedledger.BuildConfig
import dev.sharedledger.data.ledgerJson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class UpdatePhase {
    NOT_CHECKED, CHECKING, CURRENT, NO_RELEASE, AVAILABLE, INCOMPATIBLE, DOWNLOADING, VERIFYING, READY,
    INSTALL_REQUESTED, CHECK_FAILED, RATE_LIMITED, DOWNLOAD_FAILED, VERIFICATION_FAILED, INSTALL_FAILED
}

data class UpdateStatus(
    val phase: UpdatePhase = UpdatePhase.NOT_CHECKED,
    val release: UpdateRelease? = null,
    val checkedAt: Long = 0,
    val automatic: Boolean = true,
    val prompt: Boolean = false,
    val downloaded: Long = 0
)

@Serializable
private data class UpdateCache(val repository: String, val release: UpdateRelease?, val checkedAt: Long, val rateLimitUntil: Long = 0)

@Singleton
class AppUpdates internal constructor(private val context: Context, client: OkHttpClient,
                                      private val repository: String, signer: String) {
    @Inject constructor(@ApplicationContext context: Context, client: OkHttpClient) :
        this(context, client, BuildConfig.UPDATE_GITHUB_REPOSITORY, BuildConfig.UPDATE_SIGNER_SHA256)

    val configured = repository.isNotBlank() && signer.matches(Regex("[0-9a-f]{64}"))
    private val provider: UpdateProvider? = if (configured) GitHubUpdates(repository, client) else null
    private val verifier = VerifyApk(context, signer)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("ledger.apk.updates", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "apk-updates")
    private val verified = File(directory, "verified.apk")
    private var cache = prefs.getString("latest", null)?.let {
        runCatching { ledgerJson.decodeFromString<UpdateCache>(it) }.getOrNull()
    }?.takeIf { it.repository == repository } ?: UpdateCache(repository, null, 0)
    private var attempted = 0L
    private var promptedVersion: Long? = null
    private var check: Job? = null
    private var transfer: Job? = null
    private val status = MutableStateFlow(UpdateStatus(automatic = prefs.getBoolean("automatic", true)))
    val state = status.asStateFlow()
    private val cleanup = scope.launch(Dispatchers.IO) {
        directory.listFiles { file -> file.name.startsWith("incoming") }?.forEach(File::delete)
        if (cache.release?.metadata?.versionCode?.let { it > BuildConfig.VERSION_CODE } != true) verified.delete()
    }

    init {
        updateCachedStatus()
    }

    fun setAutomatic(value: Boolean) {
        prefs.edit { putBoolean("automatic", value) }
        status.update { it.copy(automatic = value, prompt = it.prompt && value) }
        if (value) foreground()
    }

    fun foreground() {
        if (status.value.automatic) checkForUpdate(manual = false)
    }

    fun checkForUpdate(manual: Boolean = true) {
        val source = provider ?: return
        if (check?.isActive == true || transfer?.isActive == true || status.value.phase == UpdatePhase.INSTALL_REQUESTED) return
        val time = System.currentTimeMillis()
        if (time < cache.rateLimitUntil) {
            if (manual) status.update { it.copy(phase = UpdatePhase.RATE_LIMITED) }
            return
        }
        if (!manual && (isRecent(time, cache.checkedAt, 24) || isRecent(time, attempted, 1))) return
        attempted = time
        check = scope.launch {
            status.update { it.copy(phase = UpdatePhase.CHECKING, prompt = false) }
            try {
                val release = source.latest()
                val oldHash = cache.release?.metadata?.sha256
                if (oldHash != release?.metadata?.sha256) withContext(Dispatchers.IO) { verified.delete() }
                cache = UpdateCache(repository, release, System.currentTimeMillis())
                save()
                updateCachedStatus()
                val candidate = available
                if (status.value.phase == UpdatePhase.AVAILABLE && candidate != null && promptedVersion != candidate.metadata.versionCode) {
                    promptedVersion = candidate.metadata.versionCode
                    status.update { it.copy(prompt = true) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (limit: RateLimited) {
                cache = cache.copy(rateLimitUntil = limit.until)
                save()
                status.update { it.copy(phase = UpdatePhase.RATE_LIMITED) }
            } catch (_: Exception) {
                status.update { it.copy(phase = UpdatePhase.CHECK_FAILED) }
            }
        }
    }

    private fun save() = prefs.edit { putString("latest", ledgerJson.encodeToString(UpdateCache.serializer(), cache)) }

    private fun updateCachedStatus() {
        val release = cache.release
        val phase = when {
            cache.checkedAt == 0L -> UpdatePhase.NOT_CHECKED
            release == null -> UpdatePhase.NO_RELEASE
            release.metadata.versionCode <= BuildConfig.VERSION_CODE -> UpdatePhase.CURRENT
            release.metadata.minSdk > Build.VERSION.SDK_INT -> UpdatePhase.INCOMPATIBLE
            verified.isFile -> UpdatePhase.READY
            else -> UpdatePhase.AVAILABLE
        }
        status.update { it.copy(phase = phase, release = release, checkedAt = cache.checkedAt, prompt = false, downloaded = 0) }
    }

    private val available get() = cache.release?.takeIf {
        it.repository == repository && it.metadata.isValid() && it.metadata.versionCode > BuildConfig.VERSION_CODE &&
            it.metadata.minSdk <= Build.VERSION.SDK_INT
    }

    fun dismissPrompt() = status.update { it.copy(prompt = false) }

    fun startDownload() {
        val source = provider ?: return
        val release = available ?: return
        if (check?.isActive == true || transfer?.isActive == true || status.value.phase == UpdatePhase.INSTALL_REQUESTED) return
        transfer = scope.launch {
            var partial: File? = null
            status.update { it.copy(phase = UpdatePhase.DOWNLOADING, prompt = false, downloaded = 0) }
            try {
                cleanup.join()
                val download = withContext(Dispatchers.IO) {
                    if (!directory.isDirectory && !directory.mkdirs()) throw java.io.IOException()
                    verified.delete()
                    File.createTempFile("incoming-", ".apk", directory).also { partial = it }
                }
                source.download(release, download) { bytes ->
                    status.update { if (it.phase == UpdatePhase.DOWNLOADING) it.copy(downloaded = bytes) else it }
                }
                status.update { it.copy(phase = UpdatePhase.VERIFYING) }
                val ready = withContext(Dispatchers.IO) {
                    verifier.verify(release, download) && download.renameTo(verified)
                }
                status.update { it.copy(phase = if (ready) UpdatePhase.READY else UpdatePhase.VERIFICATION_FAILED) }
            } catch (cancelled: CancellationException) {
                updateCachedStatus()
                throw cancelled
            } catch (_: InvalidUpdate) {
                status.update { it.copy(phase = UpdatePhase.VERIFICATION_FAILED) }
            } catch (_: Exception) {
                status.update { it.copy(phase = UpdatePhase.DOWNLOAD_FAILED) }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { partial?.delete() }
            }
        }
    }

    fun cancelDownload() {
        if (status.value.phase == UpdatePhase.DOWNLOADING || status.value.phase == UpdatePhase.VERIFYING) transfer?.cancel()
    }

    fun openInstaller(launch: (Intent) -> Unit) {
        val release = available ?: return
        if (check?.isActive == true || transfer?.isActive == true || !verified.isFile) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            try {
                launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
            } catch (_: Exception) {
                status.update { it.copy(phase = UpdatePhase.INSTALL_FAILED) }
            }
            return
        }

        transfer = scope.launch {
            status.update { it.copy(phase = UpdatePhase.VERIFYING) }
            try {
                if (!withContext(Dispatchers.IO) { verifier.verify(release, verified) }) {
                    withContext(Dispatchers.IO) { verified.delete() }
                    status.update { it.copy(phase = UpdatePhase.VERIFICATION_FAILED) }
                    return@launch
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", verified)
                // Launch Android's installer with user consent, using a single private content URI.
                val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                intent.clipData = ClipData.newRawUri("SharedLedger APK", uri)
                status.update { it.copy(phase = UpdatePhase.INSTALL_REQUESTED) }
                launch(intent)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status.update { it.copy(phase = UpdatePhase.INSTALL_FAILED) }
            }
        }
    }

    fun installerReturned() {
        if (status.value.phase == UpdatePhase.INSTALL_REQUESTED) updateCachedStatus()
    }
}

internal fun isRecent(now: Long, timestamp: Long, hours: Long): Boolean =
    timestamp > 0 && now - timestamp in 0 until TimeUnit.HOURS.toMillis(hours)
