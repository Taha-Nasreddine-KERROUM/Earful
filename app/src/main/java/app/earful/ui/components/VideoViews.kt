package app.earful.ui.components

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
    val request = remember(url, sizePx) {
        ImageRequest.Builder(ctx).data(url).crossfade(180).apply { if (sizePx != null) size(sizePx) }.build()
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
            .fillMaxWidth()
            .pressable(scaleTo = 0.975f, label = "Listen", onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) {
            RemoteImage(video.thumbnail, Modifier.matchParentSize())
            DurationBadge(video.durationSec, Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Top) {
            RemoteImage(video.channelAvatar, Modifier.size(36.dp).clip(CircleShape), sizePx = 108)
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                Text(video.title, Type.subheadStrong, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Text(Format.meta(video.channel, video.views, video.uploaded), Type.footnote, color = p.secondary, maxLines = 1)
            }
            Column {
                SaveButton(saved, onToggleSave, tint = p.secondary, hit = 40)
                QueueButton(onQueue, tint = p.secondary, hit = 40)
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
            QueueButton(onQueue, tint = p.secondary, hit = 36)
        }
    }
}

/**
 * "Add to queue". The icon briefly turns into a tick so you know it took, without a dialog.
 */
@Composable
fun QueueButton(onQueue: () -> Unit, tint: Color, hit: Int = 44, size: Int = 22) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val pop = remember { Animatable(1f) }
    var added by remember { mutableStateOf(false) }
    LaunchedEffect(added) { if (added) { kotlinx.coroutines.delay(1600); added = false } }
    Box(
        Modifier
            .size(hit.dp)
            .pressable(scaleTo = 0.85f, label = "Add to queue") {
                Haptics.confirm(view)
                onQueue()
                added = true
                scope.launch { pop.snapTo(0.7f); pop.animateTo(1f, Motion.lively()) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (added) Icons.QueueAdded else Icons.QueueAdd,
            tint = if (added) LocalPalette.current.accent else tint,
            size = size.dp,
            modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value },
            contentDescription = "Add to queue",
        )
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

/** Bookmark that pops when toggled — a small reward that confirms the action without a dialog. */
@Composable
fun SaveButton(saved: Boolean, onToggle: () -> Unit, tint: Color, size: Int = 22, hit: Int = 44) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val pop = remember { Animatable(1f) }
    val accent = LocalPalette.current.accent
    Box(
        Modifier
            .size(hit.dp) // ~Apple's minimum hit target
            .pressable(scaleTo = 0.85f, role = Role.Switch, label = if (saved) "Remove from Saved" else "Save for later") {
                if (!saved) Haptics.confirm(view) else Haptics.tick(view)
                onToggle()
                scope.launch { pop.snapTo(0.7f); pop.animateTo(1f, Motion.lively()) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (saved) Icons.BookmarkFill else Icons.Bookmark,
            tint = if (saved) accent else tint,
            size = size.dp,
            modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value },
            contentDescription = if (saved) "Saved" else "Save for later",
        )
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
