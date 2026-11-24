package dev.sharedledger.updates

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.sharedledger.BuildConfig
import dev.sharedledger.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Run with a compatible, newer signed APK staged in app-private cache. */
@RunWith(AndroidJUnit4::class)
class UpdatePipelineTest {
    @Test fun downloadValidatesARealApkAndPreparesTheAndroidInstaller() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val signer = args.getString("updateFixtureSigner")
        val hash = args.getString("updateFixtureHash")
        val version = args.getString("updateFixtureVersion")?.toLongOrNull()
        val versionName = args.getString("updateFixtureVersionName")
        assumeNotNull(signer, hash, version, versionName)
        val context = instrumentation.targetContext
        val scenario = if (args.getString("openUpdateInstaller") == "true")
            ActivityScenario.launch(MainActivity::class.java) else null
        val apk = context.cacheDir.resolve("update-fixture.apk")
        assertTrue(apk.isFile)
        val requests = AtomicInteger()
        val repository = "fixture/sharedledger"
        val address = "https://github.com/$repository/releases/download/test"
        val index = """{"versionName":"$versionName","versionCode":$version,
            "minSdk":26,"apkAsset":"update.apk","sha256":"$hash","releaseNotes":["Test update"]}"""
        val release = """{"tag_name":"test","assets":[
            {"name":"latest.json","size":${index.length},"browser_download_url":"$address/latest.json"},
            {"name":"update.apk","size":${apk.length()},"browser_download_url":"$address/update.apk"}
        ]}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            val request = chain.request()
            val body = when {
                request.url.host == "api.github.com" -> release.toResponseBody()
                request.url.pathSegments.last() == "latest.json" -> index.toResponseBody()
                else -> apk.source().buffer().asResponseBody(contentLength = apk.length())
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body).build()
        }.build()
        val settings = context.getSharedPreferences("ledger.apk.updates", android.content.Context.MODE_PRIVATE)
        settings.edit().clear().commit()
        val updates = AppUpdates(context, client, repository, requireNotNull(signer))
        instrumentation.runOnMainSync {
            updates.setAutomatic(false)
            updates.foreground()
        }
        assertEquals(0, requests.get())
        instrumentation.runOnMainSync { updates.checkForUpdate() }
        waitFor { updates.state.value.phase == UpdatePhase.AVAILABLE }
        assertEquals(version, updates.state.value.release?.metadata?.versionCode)
        assertTrue(requireNotNull(version) > BuildConfig.VERSION_CODE)
        assertTrue(updates.state.value.prompt)
        instrumentation.runOnMainSync {
            updates.dismissPrompt()
            updates.startDownload()
        }
        waitFor { updates.state.value.phase == UpdatePhase.READY }
        val verified = context.filesDir.resolve("apk-updates/verified.apk")
        val metadata = requireNotNull(updates.state.value.release)
        val verifier = VerifyApk(context, signer)
        assertTrue(verifier.verify(metadata, verified))
        assertFalse(verifier.verify(metadata.copy(metadata = metadata.metadata.copy(sha256 = "0".repeat(64))), verified))
        assertFalse(VerifyApk(context, "0".repeat(64)).verify(metadata, verified))
        val event = AtomicReference<Intent>()
        instrumentation.runOnMainSync { updates.openInstaller(event::set) }
        waitFor { event.get() != null }
        val intent = event.get()
        if (context.packageManager.canRequestPackageInstalls()) {
            assertEquals(Intent.ACTION_INSTALL_PACKAGE, intent.action)
            assertEquals("content", intent.data?.scheme)
            assertEquals("${context.packageName}.updates", intent.data?.authority)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            // Only launch the installer when requested on the disposable emulator.
            scenario?.onActivity { it.startActivityForResult(intent, 27) }
        } else {
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
            assertEquals(context.packageName, intent.data?.schemeSpecificPart)
            scenario?.close()
        }
    }

    private fun waitFor(done: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!done() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out waiting for the update pipeline", done())
    }
}
