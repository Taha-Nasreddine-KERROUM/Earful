package app.earful.ui.components

import app.earful.ui.hole.eatable
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.earful.data.Video
import app.earful.ui.theme.LocalPalette
import app.earful.ui.theme.Motion
import app.earful.ui.theme.Type
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.launch

/** Network image that fades in over a neutral fill (never a jarring pop-in). */
@Composable
fun RemoteImage(url: String?, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop, sizePx: Int? = null) {
    val ctx = LocalContext.current
    // When the picture changes (another video, a sharper copy of the same one), the old one stays
    // up until the new one has arrived, instead of a blank moment in between.
    val last = remember { arrayOfNulls<String>(1) }
    val key = url?.let { "$it@${sizePx ?: 0}" }
    val request = remember(url, sizePx) {
        ImageRequest.Builder(ctx).data(url).crossfade(180)
            .memoryCacheKey(key)
            .placeholderMemoryCacheKey(last[0]?.takeIf { it != key })
            .listener(onSuccess = { _, _ -> last[0] = key })
            .apply { if (sizePx != null) size(sizePx) }.build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier.background(LocalPalette.current.fill),
    )
}

/** The YouTube-familiar card: big 16:9 thumbnail, avatar, two-line title, meta. */
@Composable
fun VideoCard(video: Video, saved: Boolean, onOpen: () -> Unit, onToggleSave: () -> Unit, onQueue: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(
        modifier
            .eatable()
            .fillMaxWidth()
            .pressable(scaleTo = 0.975f, label = "Listen", onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) {
            RemoteImage(video.thumbnail, Modifier.matchParentSize())
            DurationBadge(video.durationSec, Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Top) {
            // The logo opens the channel (or, while it's live, tunes in).
            ChannelLogo(video, 36.dp, sizePx = 108)
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                Text(video.title, Type.subheadStrong, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                // The line under the title starts with the channel's name: tapping it opens the channel.
                val actions = LocalChannelActions.current
                Text(
                    Format.meta(video.channel, video.views, video.uploaded),
                    Type.footnote.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                    color = p.secondary, maxLines = 1,
                    modifier = Modifier.pressable(scaleTo = 0.97f, label = "Open channel") { actions.open(video) },
                )
            }
            Column {
                SaveButton(saved, onToggleSave, tint = p.secondary, hit = 40)
                QueueButton(video.id in LocalQueuedIds.current, onQueue, tint = p.secondary, hit = 40)
            }
        }
    }
}

/** Compact list row used in Saved. */
@Composable
fun VideoRow(video: Video, saved: Boolean, onOpen: () -> Unit, onToggleSave: () -> Unit, onQueue: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(
        modifier
            .eatable()
            .fillMaxWidth()
            .pressable(scaleTo = 0.98f, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
            RemoteImage(video.thumbnail, Modifier.matchParentSize(), sizePx = 384)
            DurationBadge(video.durationSec, Modifier.align(Alignment.BottomEnd).padding(5.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(video.title, Type.subheadStrong, maxLines = 2)
            Spacer(Modifier.height(3.dp))
            Text(video.channel, Type.footnote, color = p.secondary, maxLines = 1)
        }
        Column {
            SaveButton(saved, onToggleSave, tint = if (saved) p.accent else p.secondary, hit = 36)
            QueueButton(video.id in LocalQueuedIds.current, onQueue, tint = p.secondary, hit = 36)
        }
    }
}

@Composable
fun DurationBadge(sec: Long, modifier: Modifier = Modifier) {
    if (sec <= 0) return
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(Format.duration(sec), Type.captionStrong.copy(fontFeatureSettings = "tnum"), color = Color.White)
    }
}

@Composable
fun VideoCardSkeleton() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Skeleton(Modifier.fillMaxWidth().aspectRatio(16f / 9f), RoundedCornerShape(14.dp))
        Row(Modifier.padding(top = 12.dp)) {
            Skeleton(Modifier.size(36.dp), CircleShape)
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Skeleton(Modifier.fillMaxWidth(0.9f).height(14.dp), RoundedCornerShape(4.dp))
                Skeleton(Modifier.fillMaxWidth(0.55f).height(12.dp), RoundedCornerShape(4.dp))
            }
        }
    }
}
