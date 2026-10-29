package app.earful.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import app.earful.ui.theme.LocalPalette
import app.earful.ui.theme.Motion
import androidx.compose.ui.composed

/** Text with Apple's defaults: label colour, ellipsis, no font padding. */
@Composable
fun Text(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = LocalPalette.current.label,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Apple-style press feedback: the element dims and settles slightly into the screen.
 * No ripple — the response is on the object you touched, not painted over it.
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    scaleTo: Float = 0.96f,
    role: Role = Role.Button,
    label: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) scaleTo else 1f, Motion.snappy(), label = "press")
    val a by animateFloatAsState(if (pressed) 0.6f else 1f, Motion.snappy(), label = "pressAlpha")
    this
        .graphicsLayer { scaleX = s; scaleY = s; alpha = a }
        .clickable(source, indication = null, enabled = enabled, role = role, onClickLabel = label, onClick = onClick)
}

/** Placeholder block that breathes gently while content loads (perceived wait drops vs. a spinner). */
@Composable
fun Skeleton(modifier: Modifier, shape: Shape) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(modifier.graphicsLayer { alpha = a }.clip(shape).background(LocalPalette.current.fill))
}

object Haptics {
    /** A light tick — seeking, toggles. */
    fun tick(v: View) { v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    /** Crossing a threshold (dismiss, snap). */
    fun threshold(v: View) {
        v.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }
    /** Something completed (saved). */
    fun confirm(v: View) {
        v.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }
}

object Format {
    fun duration(totalSec: Long): String {
        if (totalSec < 0) return "--:--"
        val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun views(n: Long): String? = when {
        n < 0 -> null
        n < 1_000 -> "$n views"
        n < 1_000_000 -> trim(n / 1_000.0) + "K views"
        n < 1_000_000_000 -> trim(n / 1_000_000.0) + "M views"
        else -> trim(n / 1_000_000_000.0) + "B views"
    }

    private fun trim(d: Double) = if (d >= 10) d.toLong().toString() else "%.1f".format(d).removeSuffix(".0")

    /**
     * "Channel · 1.2M views · 3 days ago". The channel's name is set apart (Unicode isolate), so a
     * name in Arabic or Hebrew reads right to left on its own without flipping the rest of the
     * line: the line always reads like English, name first.
     */
    fun meta(channel: String, views: Long, uploaded: String?) =
        listOfNotNull(channel.ifEmpty { null }?.let { "\u2068$it\u2069" }, views(views), uploaded?.ifEmpty { null }).joinToString(" · ")
}
