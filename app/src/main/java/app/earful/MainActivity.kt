package app.earful

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.earful.data.Video
import app.earful.playback.PlayerConnection
import app.earful.update.Updater
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.earful.ui.AppRoot
import app.earful.ui.AppSignals
import app.earful.ui.OpeningLogo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.earful.ui.theme.EarfulTheme

class MainActivity : ComponentActivity() {
    private lateinit var conn: PlayerConnection
    private val signals = AppSignals()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        conn = PlayerConnection(applicationContext)
        handle(intent)
        // The opening plays when the app is started fresh from its icon (not when it comes back
        // after turning the phone, or opens straight onto the player from the notification).
        val opening = savedInstanceState == null && intent?.action == Intent.ACTION_MAIN &&
            intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) != true
        setContent {
            EarfulTheme {
                Box(Modifier.fillMaxSize()) {
                    AppRoot(conn, signals)
                    var showOpening by rememberSaveable { mutableStateOf(opening) }
                    if (showOpening) OpeningLogo { showOpening = false }
                }
            }
        }
        // Updates: one quiet check when the app is opened fresh (not when you just switch back to
        // it), then once an hour while it stays open on screen. It runs off the main thread a few
        // seconds after launch, so it never slows the app down or gets in your way.
        lifecycleScope.launch {
            delay(3_000)
            Updater.check(this@MainActivity)
            while (true) {
                delay(60 * 60 * 1000L)
                if (Updater.foreground) Updater.check(this@MainActivity)
            }
        }
    }

    override fun onStart() { Updater.foreground = true; super.onStart(); conn.connect() }
    override fun onStop() { Updater.foreground = false; conn.disconnect(); super.onStop() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) signals.openPlayer++
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            Video.idFromUrl(text)?.let { id ->
                signals.playRequest = Video(id, "Loading…", "", Video.fallbackThumb(id), 0)
            }
        }
    }

    companion object { const val EXTRA_OPEN_PLAYER = "open_player" }
}
