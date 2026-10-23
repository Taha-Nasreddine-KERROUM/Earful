package app.earful

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.earful.data.Video
import app.earful.playback.PlayerConnection
import app.earful.update.Updater
import app.earful.ui.AppRoot
import app.earful.ui.AppSignals
import app.earful.ui.theme.EarfulTheme

class MainActivity : ComponentActivity() {
    private lateinit var conn: PlayerConnection
    private val signals = AppSignals()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        conn = PlayerConnection(applicationContext)
        handle(intent)
        setContent { EarfulTheme { AppRoot(conn, signals) } }
    }

    override fun onStart() { super.onStart(); conn.connect(); Updater.check(this) }
    override fun onStop() { conn.disconnect(); super.onStop() }

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
