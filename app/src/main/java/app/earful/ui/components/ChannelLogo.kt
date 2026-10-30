package app.earful.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.earful.data.LiveNow
import app.earful.data.Video

/** What tapping a channel does, handed down from the app's root. */
@Immutable
class ChannelActions(
    /** Opens the channel's page (from any of its videos). */
    val open: (Video) -> Unit = {},
    /** Starts listening to a live broadcast. */
    val listenLive: (Video) -> Unit = {},
)

val LocalChannelActions = staticCompositionLocalOf { ChannelActions() }

/** YouTube's live red. */
val LiveRed = Color(0xFFFF0033)

/**
 * A channel's round logo. While the channel is live, a red ring goes round it with a small LIVE
 * tag, and tapping it tunes in; otherwise tapping it opens the channel.
 */
@Composable
fun ChannelLogo(
    video: Video,
    size: Dp,
    modifier: Modifier = Modifier,
    avatar: String? = video.channelAvatar,
    sizePx: Int = (size.value * 3).toInt(),
    onOpen: (() -> Unit)? = null,
    /** Made by several channels together: their pictures, overlapping (as YouTube shows them). */
    together: List<app.earful.data.YouTube.Collab>? = null,
) {
    val actions = LocalChannelActions.current
    val version by LiveNow.version.collectAsStateWithLifecycle()
    val live = remember(version, video.channelUrl) { LiveNow.of(video.channelUrl) }
    val ring = (size.value / 18f).coerceIn(2f, 3.5f).dp
    Box(
        modifier.size(size)
            .pressable(scaleTo = 0.9f, label = if (live != null) "Listen live" else "Open channel") {
                if (live != null) actions.listenLive(live) else onOpen?.invoke() ?: actions.open(video)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (live != null) {
            Box(Modifier.size(size).border(ring, LiveRed, CircleShape))
            RemoteImage(avatar, Modifier.size(size - ring * 3).clip(CircleShape), sizePx = sizePx)
            Box(
                Modifier.align(Alignment.BottomCenter).offset(y = ring + 1.dp)
                    .clip(RoundedCornerShape(3.dp)).background(LiveRed)
                    .padding(horizontal = (size.value / 14f).coerceIn(2.5f, 6f).dp)
            ) {
                Text(
                    "LIVE",
                    TextStyle(fontSize = (size.value / 4.6f).coerceIn(7f, 12f).sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
                    color = Color.White, maxLines = 1,
                )
            }
        } else if (together != null && together.size > 1) {
            // two pictures, one up-left and one down-right in front of it, parted by a thin ring
            val d = size * 0.74f
            val ring = (size.value * 0.06f).coerceAtLeast(1.5f).dp
            val bg = app.earful.ui.theme.LocalPalette.current.bg
            RemoteImage(together[0].avatar ?: avatar, Modifier.align(Alignment.TopStart).size(d).clip(CircleShape), sizePx = sizePx)
            Box(Modifier.align(Alignment.BottomEnd).size(d + ring * 2).offset(x = ring, y = ring).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                RemoteImage(together[1].avatar, Modifier.size(d).clip(CircleShape), sizePx = sizePx)
            }
        } else {
            RemoteImage(avatar, Modifier.size(size).clip(CircleShape), sizePx = sizePx)
        }
    }
}

@Composable
private fun <T> remember(a: Any?, b: Any?, calc: () -> T): T = androidx.compose.runtime.remember(a, b, calc)

/**
 * The channels [video] was made by, when it's several (null otherwise). Looked up only when its
 * channel line names more than one, or already known from playing it; remembered after.
 */
@Composable
fun rememberTogether(video: Video): List<app.earful.data.YouTube.Collab>? {
    if (video.isLive) return null
    val id = video.youtubeId
    var list by androidx.compose.runtime.remember(id) { androidx.compose.runtime.mutableStateOf(app.earful.data.YouTube.knownCollaborators(id)) }
    androidx.compose.runtime.LaunchedEffect(id) {
        if (list == null && app.earful.data.YouTube.looksShared(video.channel)) {
            list = runCatching { app.earful.data.YouTube.collaborators(id) }.getOrNull()
        }
    }
    return list?.takeIf { it.size > 1 }
}
