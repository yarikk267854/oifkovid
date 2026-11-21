package com.videosaver.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.videosaver.app.ui.MainScreen
import com.videosaver.app.ui.VideoSaverTheme

class MainActivity : ComponentActivity() {

    private var sharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            VideoSaverTheme {
                MainScreen(
                    sharedText = sharedText,
                    onSharedConsumed = { sharedText = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        DownloadRepository.uiVisible = true
    }

    override fun onStop() {
        DownloadRepository.uiVisible = false
        super.onStop()
    }

    /** Ссылка, отправленная через «Поделиться» → сразу ставим на загрузку. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        }
    }
}
