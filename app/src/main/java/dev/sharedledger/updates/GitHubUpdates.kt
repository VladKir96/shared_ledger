package dev.sharedledger.updates

import dev.sharedledger.data.ledgerJson
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class ApkMetadata(
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val apkAsset: String,
    val sha256: String,
    val releaseNotes: List<String> = emptyList()
) {
    fun isValid() = versionCode in 1..2_100_000_000L && versionName.length in 1..64 && minSdk in 1..1_000 &&
        apkAsset.matches(Regex("[a-zA-Z0-9._-]{1,120}\\.apk")) && sha256.matches(Regex("[0-9a-f]{64}")) &&
        releaseNotes.size <= 30 && releaseNotes.all { it.length <= 400 }
}

@Serializable
data class UpdateRelease(val metadata: ApkMetadata, val url: String, val size: Long, val repository: String)

internal interface UpdateProvider {
    suspend fun latest(): UpdateRelease?
    suspend fun download(release: UpdateRelease, target: File, progress: (Long) -> Unit)
}

internal class RateLimited(val until: Long) : IOException()
internal class InvalidUpdate : IOException()

@Serializable
private data class ReleaseInfo(@SerialName("tag_name") val tag: String, val assets: List<ReleaseAsset>)
@Serializable
private data class ReleaseAsset(
    val name: String, val size: Long, @SerialName("browser_download_url") val url: String
)

internal class GitHubUpdates(repository: String, client: OkHttpClient) : UpdateProvider {
    private val repo = repository.split('/')
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.MINUTES).readTimeout(25, TimeUnit.SECONDS).build()
    private val api = "https://api.github.com/".toHttpUrl().newBuilder()
        .addPathSegment("repos").addPathSegment(repo[0]).addPathSegment(repo[1])
        .addPathSegments("releases/latest").build()

    override suspend fun latest(): UpdateRelease? {
        val data = fetch(api, apiRequest = true) { response ->
            // GitHub also uses 404 for a private or unpublished repository.
            if (response.code == 404) return@fetch null
            ledgerJson.decodeFromString<ReleaseInfo>(body(response).boundedText(1024 * 1024))
        } ?: return null
        val index = data.assets.singleOrNull { it.name == "latest.json" }
            ?: throw InvalidUpdate()
        if (index.size !in 1..65536) throw InvalidUpdate()
        val indexUrl = assetUrl(index, data.tag)
        val metadata = fetch(indexUrl) { response ->
            ledgerJson.decodeFromString<ApkMetadata>(body(response).boundedText(65536))
        }
        if (!metadata.isValid()) throw InvalidUpdate()
        val apk = data.assets.singleOrNull { it.name == metadata.apkAsset && it.size in 1..MAX_APK }
            ?: throw InvalidUpdate()
        return UpdateRelease(metadata, assetUrl(apk, data.tag).toString(), apk.size, repo.joinToString("/"))
    }

    override suspend fun download(release: UpdateRelease, target: File, progress: (Long) -> Unit) {
        val url = release.url.toHttpUrlOrNull() ?: throw InvalidUpdate()
        if (!release.metadata.isValid() || release.size !in 1..MAX_APK || release.repository != repo.joinToString("/") ||
            !isGitHubAsset(url, repo, release.metadata.apkAsset)) throw InvalidUpdate()
        fetch(url) { response ->
            val body = body(response)
            if (body.contentLength() >= 0 && body.contentLength() != release.size) throw InvalidUpdate()
            body.byteStream().use { source ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    var lastUpdate = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count == -1) break
                        bytes += count
                        if (bytes > release.size) throw InvalidUpdate()
                        output.write(buffer, 0, count)
                        val now = System.nanoTime()
                        if (now - lastUpdate >= 120_000_000L) {
                            progress(bytes)
                            lastUpdate = now
                        }
                    }
                    if (bytes != release.size) throw InvalidUpdate()
                    progress(bytes)
                }
            }
        }
    }

    private fun assetUrl(asset: ReleaseAsset, tag: String): HttpUrl {
        val url = asset.url.toHttpUrlOrNull() ?: throw InvalidUpdate()
        if (!isGitHubAsset(url, repo, asset.name) || url.pathSegments[4] != tag) throw InvalidUpdate()
        return url
    }

    private suspend fun <T> fetch(url: HttpUrl, apiRequest: Boolean = false, consume: (Response) -> T): T {
        var target = url
        repeat(5) {
            val request = Request.Builder().url(target)
                .header("Accept", if (apiRequest) "application/vnd.github+json" else "application/octet-stream")
            if (apiRequest) request.header("X-GitHub-Api-Version", "2022-11-28")
            val result = client.newCall(request.build()).await { response ->
                when {
                    response.code in REDIRECTS -> {
                        if (apiRequest) throw InvalidUpdate()
                        val next = target.resolve(response.header("Location") ?: "") ?: throw InvalidUpdate()
                        if (!isAssetRedirect(next)) throw InvalidUpdate()
                        FetchResult.Redirect(next)
                    }
                    response.code == 429 || response.code == 403 &&
                        (response.header("X-RateLimit-Remaining") == "0" || response.header("Retry-After") != null) ->
                        throw RateLimited(retryAt(response, System.currentTimeMillis()))
                    else -> FetchResult.Value(consume(response))
                }
            }
            when (result) {
                is FetchResult.Redirect -> target = result.url
                is FetchResult.Value -> return result.value
            }
        }
        throw InvalidUpdate()
    }

    private fun body(response: Response): ResponseBody {
        if (!response.isSuccessful) throw IOException("GitHub returned HTTP ${response.code}")
        return response.body ?: throw InvalidUpdate()
    }

    private sealed interface FetchResult<out T> {
        data class Redirect(val url: HttpUrl) : FetchResult<Nothing>
        data class Value<T>(val value: T) : FetchResult<T>
    }

    companion object {
        private const val MAX_APK = 250L * 1024 * 1024
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}

private fun ResponseBody.boundedText(limit: Long): String = source().let { input ->
    if (input.request(limit + 1)) throw InvalidUpdate()
    input.readUtf8()
}

private suspend fun <T> Call.await(consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) {
                response.close()
                return
            }
            try {
                val result = response.use(consume)
                continuation.resume(result)
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
            }
        }
    })
}

private fun HttpUrl.secure() = isHttps && port == 443 && username.isEmpty() && password.isEmpty() && fragment == null
internal fun isGitHubAsset(url: HttpUrl, repo: List<String>, asset: String): Boolean =
    url.secure() && url.host == "github.com" && url.query == null &&
        url.pathSegments.size == 6 && url.pathSegments.take(2).zip(repo).all { (a, b) -> a.equals(b, ignoreCase = true) } &&
        url.pathSegments[2] == "releases" && url.pathSegments[3] == "download" && url.pathSegments[5] == asset

internal fun isAssetRedirect(url: HttpUrl): Boolean =
    url.secure() && url.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com",
        "github-releases.githubusercontent.com")

internal fun retryAt(response: Response, now: Long): Long {
    val retry = response.header("Retry-After")
    val fromRetry = retry?.toLongOrNull()?.coerceIn(0, 86400)?.let { now + it * 1000L }
        ?: retry?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
    val reset = response.header("X-RateLimit-Reset")?.toLongOrNull()?.takeIf { it in 0..Long.MAX_VALUE / 1000 }?.times(1000)
    return maxOf(fromRetry ?: 0, reset ?: 0, now + 60_000).coerceAtMost(now + TimeUnit.DAYS.toMillis(1))
}
