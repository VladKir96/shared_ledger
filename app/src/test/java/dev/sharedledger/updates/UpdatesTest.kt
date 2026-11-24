package dev.sharedledger.updates

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UpdatesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val signer = "ab".repeat(32)
    private val apkUrl = "https://github.com/owner/repo/releases/download/v0.2.0/sharedledger-0.2.0.apk"
    private val metadata = ApkMetadata("0.2.0", 2, "dev.sharedledger", 26, apkUrl, 3, "a".repeat(64), listOf("Changes"))
    private val installed = ApkIdentity("dev.sharedledger", "0.1.0", 1, 26, setOf(signer))
    private val expected = installed.copy(versionName = "0.2.0", version = 2)

    @Test fun installedAndArchiveMustMatchThePinnedSignerAndActualBuildMetadata() {
        assertTrue(verifyIdentity(metadata, expected, installed, signer, 35))
        assertFalse(verifyIdentity(metadata, expected.copy(packageName = "example.unrelated"), installed, signer, 35))
        assertFalse(verifyIdentity(metadata, expected.copy(version = 1), installed, signer, 35))
        assertFalse(verifyIdentity(metadata, expected.copy(version = 3), installed, signer, 35))
        assertFalse(verifyIdentity(metadata, expected.copy(versionName = "different"), installed, signer, 35))
        assertFalse(verifyIdentity(metadata, expected.copy(minimumSdk = 28), installed, signer, 35))
        assertFalse(verifyIdentity(metadata, expected, installed, signer, 25))
        assertFalse(verifyIdentity(metadata, expected.copy(signers = setOf("cd".repeat(32))), installed, signer, 35))
        assertFalse(verifyIdentity(metadata, expected.copy(signers = emptySet()), installed, signer, 35))
        assertFalse(verifyIdentity(metadata, expected, installed.copy(signers = setOf("cd".repeat(32))), signer, 35))
        assertFalse(verifyIdentity(metadata.copy(sha256 = "invalid"), expected, installed, signer, 35))
    }

    @Test fun latestUsesExplicitAndroidVersionAndDownloadsViaAnAllowedRedirect() = runBlocking {
        val client = intercepted { request ->
            when {
                request.url.toString() == manifestUrl("owner/repo") -> response(request, 302,
                    headers = mapOf("Location" to "../../download/v0.2.0/latest.json"))
                request.url.encodedPath == "/owner/repo/releases/download/v0.2.0/latest.json" -> response(request, text = metadataJson)
                request.url.toString() == apkUrl -> response(request, 302,
                    headers = mapOf("Location" to "https://release-assets.githubusercontent.com/object"))
                else -> response(request, text = "APK")
            }
        }
        val updater = GitHubUpdates("owner/repo", client)
        val release = checkNotNull(updater.latest())
        assertEquals(2L, release.metadata.versionCode)
        assertEquals("0.2.0", release.metadata.versionName)
        val file = temporary.newFile()
        val progress = mutableListOf<Long>()
        updater.download(release, file, progress::add)
        assertEquals("APK", file.readText())
        assertEquals(3L, progress.last())
    }

    @Test fun rejectsExternalAssetHostsUnexpectedRedirectsAndTruncatedDownloads() = runBlocking {
        val hosts = listOf("http://release-assets.githubusercontent.com/x", "https://release-assets.githubusercontent.com.evil/x",
            "https://release-assets.githubusercontent.com:444/x", "https://u@release-assets.githubusercontent.com/x",
            "https://example.test/private")
        assertTrue(hosts.none { isAssetRedirect(it.toHttpUrl()) })
        assertFalse(isGitHubAsset("https://github.com/other/repo/releases/download/v/a.apk".toHttpUrl(), listOf("owner", "repo"), "a.apk"))
        val release = UpdateRelease(metadata, "owner/repo")
        val truncated = GitHubUpdates("owner/repo", intercepted { response(it, text = "AP") })
        val redirect = GitHubUpdates("owner/repo", intercepted { response(it, 302, headers = mapOf("Location" to hosts.last())) })
        for (updater in listOf(truncated, redirect)) {
            val failure = runCatching { updater.download(release, temporary.newFile()) {} }.exceptionOrNull()
            assertTrue(failure is InvalidUpdate)
        }
    }

    @Test fun distinguishesUnavailableAndRateLimitedRequestsAndGatesAutomaticChecks() = runBlocking {
        val offline = GitHubUpdates("owner/repo", intercepted { throw IOException("offline") })
        assertTrue(runCatching { offline.latest() }.exceptionOrNull() is IOException)
        val missing = GitHubUpdates("owner/repo", intercepted { response(it, 404) })
        assertNull(missing.latest())
        val limited = GitHubUpdates("owner/repo", intercepted { response(it, 403, headers = mapOf("Retry-After" to "120")) })
        val error = runCatching { limited.latest() }.exceptionOrNull()
        assertTrue(error is RateLimited && error.until >= System.currentTimeMillis() + 118_000)
        val day = TimeUnit.DAYS.toMillis(1)
        assertTrue(isRecent(day, 1, 24))
        assertFalse(isRecent(day + 1, 1, 24))
        assertFalse(isRecent(10, 11, 24))
        assertFalse(isRecent(10, 0, 24))
    }

    @Test fun cancelingTheDownloadCancelsItsHttpCall() = runBlocking {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val client = intercepted { request ->
            entered.countDown()
            check(cancelled.await(5, TimeUnit.SECONDS))
            response(request, text = "APK")
        }.newBuilder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.countDown() }
        }).build()
        val release = UpdateRelease(metadata, "owner/repo")
        val file = temporary.newFile()
        val pending = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            GitHubUpdates("owner/repo", client).download(release, file) {}
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        pending.cancelAndJoin()
        assertEquals(0L, cancelled.count)
    }

    private fun intercepted(respond: (Request) -> Response) = OkHttpClient.Builder()
        .addInterceptor { chain -> respond(chain.request()) }.build()

    private fun response(request: Request, code: Int = 200, text: String = "", headers: Map<String, String> = emptyMap()): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(text.toResponseBody()).apply { headers.forEach { (key, value) -> header(key, value) } }.build()

    private val metadataJson = """{"versionName":"0.2.0","versionCode":2,"packageName":"dev.sharedledger","minSdk":26,
        "apkUrl":"$apkUrl","apkSize":3,"sha256":"${"a".repeat(64)}","releaseNotes":["Changes"]}"""

    @Test fun invalidManifestCannotPointOutsideItsVersionRepositoryAndPackage() = runBlocking {
        val invalid = listOf(
            metadataJson.replace("dev.sharedledger", "com.other.app"),
            metadataJson.replace("owner/repo", "other/repo"),
            metadataJson.replace("v0.2.0/", "v0.1.0/"),
            metadataJson.replace("https://github.com", "http://github.com"),
            metadataJson.replace("\"apkSize\":3", "\"apkSize\":0"),
            """{"versionName":"""
        )
        invalid.forEach { content ->
            val updater = GitHubUpdates("owner/repo", intercepted { response(it, text = content) })
            assertTrue(runCatching { updater.latest() }.isFailure)
        }
    }
}
