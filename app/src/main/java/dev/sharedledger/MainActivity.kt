package dev.sharedledger

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.sharedledger.sync.Background
import dev.sharedledger.domain.LedgerFormats
import javax.inject.Inject
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import dev.sharedledger.presentation.LedgerApp
import dev.sharedledger.presentation.LedgerViewModel
import dev.sharedledger.sync.parseAuthLink
import dev.sharedledger.push.PushNotifications
import dev.sharedledger.push.isPushEvent
import dev.sharedledger.updates.AppUpdates

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var background: Background
    @Inject lateinit var push: PushNotifications
    @Inject lateinit var updates: AppUpdates
    private val ledgerViewModel: LedgerViewModel by viewModels()
    private var incoming by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LedgerFormats.locale = android.content.res.Resources.getSystem().configuration.locales[0]
        enableEdgeToEdge()
        handleLink(intent)
        setContent { LedgerApp(ledgerViewModel, incoming) { incoming = null } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
        setIntent(intent)
    }

    private fun handleLink(intent: Intent) {
        if (intent.action == PushNotifications.ACTION_PUSH) {
            val event = intent.data?.lastPathSegment
            intent.data = null
            if (event != null && isPushEvent(event)) ledgerViewModel.acceptPush(event)
            return
        }
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val authLink = parseAuthLink(uri.toString())
        if (authLink != null) {
            intent.data = null
            ledgerViewModel.acceptAuthLink(authLink)
        } else if (uri.scheme == "sharedledger" && uri.host == "invite") {
            incoming = uri
        }
    }

    override fun onStart() {
        super.onStart()
        background.foreground()
        push.enqueue()
        updates.foreground()
    }
}
