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
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.launch

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
 *
 * Costs nothing until it's first pressed: no layer, no animation, nothing composed per item
 * (a feed has five of these on every card). The look is made the moment a finger lands.
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    scaleTo: Float = 0.96f,
    role: Role = Role.Button,
    label: String? = null,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = null,
    indication = PressSink.of(scaleTo),
    enabled = enabled,
    onClickLabel = label,
    role = role,
    onClick = onClick,
)

/** The press look, as a light drawing step that only exists once something is pressed. */
private class PressSink private constructor(private val scaleTo: Float) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressSinkNode(interactionSource, scaleTo)
    override fun equals(other: Any?) = other is PressSink && other.scaleTo == scaleTo
    override fun hashCode() = scaleTo.hashCode()

    companion object {
        private val made = HashMap<Float, PressSink>()
        fun of(scaleTo: Float): PressSink = synchronized(made) { made.getOrPut(scaleTo) { PressSink(scaleTo) } }
    }
}

private class PressSinkNode(private val source: InteractionSource, private val scaleTo: Float) :
    Modifier.Node(), DrawModifierNode {
    private val press = Animatable(0f)
    private val paint = Paint()

    override fun onAttach() {
        coroutineScope.launch {
            var held = 0
            source.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> held++
                    is PressInteraction.Release, is PressInteraction.Cancel -> held = (held - 1).coerceAtLeast(0)
                    else -> return@collect
                }
                val to = if (held > 0) 1f else 0f
                launch { press.animateTo(to, Motion.snappy()) }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val p = press.value
        if (p <= 0.001f) { drawContent(); return }
        val s = 1f - (1f - scaleTo) * p
        paint.alpha = 1f - 0.4f * p
        scale(s) {
            drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), paint) }
            this@draw.drawContent()
            drawIntoCanvas { it.restore() }
        }
    }
}

/** Placeholder block that breathes gently while content loads (perceived wait drops vs. a spinner). */
@Composable
fun Skeleton(modifier: Modifier, shape: Shape) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    // (one flat shape: faded straight, without painting it off to the side first)
    Box(modifier.graphicsLayer { alpha = a; compositingStrategy = CompositingStrategy.ModulateAlpha }.clip(shape).background(LocalPalette.current.fill))
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
