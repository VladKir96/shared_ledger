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
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.AndroidEntryPoint
import dev.sharedledger.presentation.LedgerApp
import dev.sharedledger.presentation.LedgerViewModel

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var background: Background
    private var incoming by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LedgerFormats.locale = android.content.res.Resources.getSystem().configuration.locales[0]
        enableEdgeToEdge()
        incoming = intent.data
        setContent { LedgerApp(viewModel<LedgerViewModel>(), incoming) { incoming = null } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming = intent.data
    }

    override fun onStart() {
        super.onStart()
        background.foreground()
    }
}
