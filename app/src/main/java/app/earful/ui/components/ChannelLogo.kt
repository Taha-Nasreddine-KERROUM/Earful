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
        } else {
            RemoteImage(avatar, Modifier.size(size).clip(CircleShape), sizePx = sizePx)
        }
    }
}

@Composable
private fun <T> remember(a: Any?, b: Any?, calc: () -> T): T = androidx.compose.runtime.remember(a, b, calc)
