package dev.sharedledger

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AuthDeepLinkTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @After fun finishActivity() {
        instrumentation.runOnMainSync { resumedActivity()?.finishAndRemoveTask() }
    }

    @Test fun coldConfirmationLinkShowsExpiredEmailAndSurvivesActivityRecreation() {
        context.startActivity(callback("confirm#error=access_denied&error_code=otp_expired&error_description=private"))
        awaitText("Verify email")
        ui.onNodeWithText("This email link has expired or has already been used. Request a new one.").assertIsDisplayed()
        ui.onNodeWithText("private", substring = true).assertDoesNotExist()

        val recreated = CountDownLatch(1)
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val original = activityOnMain()
        val listener = ActivityLifecycleCallback { activity, stage ->
            if (activity is MainActivity && activity !== original && stage == Stage.RESUMED) recreated.countDown()
        }
        instrumentation.runOnMainSync {
            assertNull(original.intent.data)
            monitor.addLifecycleCallback(listener)
            original.recreate()
        }
        try {
            assertTrue("Activity was not recreated", recreated.await(10, TimeUnit.SECONDS))
            awaitText("Verify email")
        } finally {
            instrumentation.runOnMainSync { monitor.removeLifecycleCallback(listener) }
        }
    }

    @Test fun warmAndBackgroundLinksReachTheExistingActivity() {
        val launcher = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        context.startActivity(launcher)
        awaitText("Home")
        val original = activityOnMain()

        context.startActivity(callback("recovery?error_code=otp_expired"))
        awaitText("Reset password")
        ui.onNodeWithText("This email link has expired or has already been used. Request a new one.").assertIsDisplayed()
        assertSame(original, activityOnMain())

        val stopped = CountDownLatch(1)
        instrumentation.runOnMainSync {
            original.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) stopped.countDown()
            })
            assertTrue(original.moveTaskToBack(true))
        }
        assertTrue("Activity did not enter the background", stopped.await(10, TimeUnit.SECONDS))
        context.startActivity(callback("confirm?code=short"))
        awaitText("Verify email")
        ui.onNodeWithText("This email link is invalid. Request a new one in Cloud sync.").assertIsDisplayed()
        assertSame(original, activityOnMain())
    }

    private fun callback(path: String) = Intent(Intent.ACTION_VIEW, Uri.parse("sharedledger://auth/$path"))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .setPackage(context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun activityOnMain(): MainActivity {
        lateinit var activity: MainActivity
        instrumentation.runOnMainSync { activity = checkNotNull(resumedActivity()) }
        return activity
    }

    private fun resumedActivity() = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()

    private fun awaitText(value: String) {
        ui.waitUntil(10_000) { ui.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(value).assertIsDisplayed()
    }
}
