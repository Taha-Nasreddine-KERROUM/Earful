package app.earful.ui.saved

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.earful.data.Library
import app.earful.data.Video
import app.earful.ui.components.Icon
import app.earful.ui.components.Icons
import app.earful.ui.components.Text
import app.earful.ui.components.VideoRow
import app.earful.ui.theme.LocalPalette
import app.earful.ui.theme.Type

@Composable
fun SavedScreen(bottomPadding: Dp, onPlay: (Video) -> Unit, onQueue: (Video) -> Unit, onRemoved: (Video) -> Unit) {
    val p = LocalPalette.current
    val saved by Library.saved.collectAsStateWithLifecycle()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = statusTop + 8.dp, bottom = bottomPadding + 16.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Saved", Type.largeTitle)
                if (saved.isNotEmpty()) {
                    val totalMin = saved.sumOf { it.durationSec } / 60
                    val time = if (totalMin >= 60) "${totalMin / 60} h ${totalMin % 60} min" else "$totalMin min"
                    Text("${saved.size} to listen to · $time", Type.subhead, color = p.secondary)
                }
            }
        }
        if (saved.isEmpty()) item { Empty() }
        items(saved, key = { it.id }) { v ->
            VideoRow(v, saved = true, onOpen = { onPlay(v) }, onToggleSave = { Library.removeSaved(v.id); onRemoved(v) }, onQueue = { onQueue(v) })
        }
    }
}

@Composable
private fun Empty() {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(p.fill), contentAlignment = Alignment.Center) {
            Icon(Icons.Bookmark, p.secondary, size = 32.dp)
        }
        Spacer(Modifier.height(18.dp))
        Text("Nothing saved yet", Type.title3)
        Spacer(Modifier.height(6.dp))
        Text(
            "Tap the bookmark on any video and it will wait for you here.",
            Type.subhead.copy(textAlign = TextAlign.Center), color = p.secondary,
        )
    }
}
