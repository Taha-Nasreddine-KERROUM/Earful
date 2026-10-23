package app.earful.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.earful.update.UpdateState
import app.earful.update.Updater
import app.earful.ui.theme.LocalPalette
import app.earful.ui.theme.Type

/**
 * Shown at the top of Home only when there's something to do. One button; the download
 * progress lives right in the card so you never wonder what's happening.
 */
@Composable
fun UpdateCard(modifier: Modifier = Modifier) {
    val state by Updater.state.collectAsStateWithLifecycle()
    if (state is UpdateState.Idle) return
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val (title, body, action) = when (val s = state) {
        is UpdateState.Available -> Triple(
            "Update ready",
            "Keeps YouTube working" + if (s.sizeBytes > 0) " · %.1f MB".format(s.sizeBytes / 1_048_576.0) else "",
            "Install",
        )
        is UpdateState.Downloading -> Triple("Downloading update", "${(s.fraction * 100).toInt()}%", null)
        is UpdateState.Ready -> Triple("Update downloaded", "Tap Install, then confirm", "Install")
        UpdateState.Failed -> Triple("Couldn't download the update", "Check your connection", "Try again")
        UpdateState.Idle -> return
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(p.grouped)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(p.accent), contentAlignment = Alignment.Center) {
            Icon(Icons.ChevronUp, Color.White, size = 18.dp)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, Type.subheadStrong, maxLines = 1)
            Text(body, Type.footnote.copy(fontFeatureSettings = "tnum"), color = p.secondary, maxLines = 1)
            val s = state
            if (s is UpdateState.Downloading) {
                val f by animateFloatAsState(s.fraction, label = "dl")
                Spacer(Modifier.height(6.dp))
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(p.fill, cornerRadius = r)
                    drawRoundRect(p.accent, size = Size(size.width * f, size.height), cornerRadius = r)
                }
            }
        }
        if (action != null) {
            Box(
                Modifier.pressable {
                    if (state is UpdateState.Failed) { Updater.dismissFailure(); Updater.check(ctx, force = true) }
                    else Updater.downloadAndInstall(ctx)
                }.clip(CircleShape).background(p.accent).padding(horizontal = 14.dp, vertical = 7.dp)
            ) { Text(action, Type.subheadStrong, color = Color.White) }
        }
    }
}

