package app.earful.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import app.earful.ui.components.Icon
import app.earful.ui.components.Icons
import app.earful.ui.components.Text
import app.earful.ui.components.Water
import app.earful.ui.theme.LocalPalette
import app.earful.ui.theme.Motion
import app.earful.ui.theme.Type
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

internal enum class Tab { Home, Saved }

/** How deep the tab you're not on rests (just under the water), and how deep a tapped one dips. */
private const val UNDER = 10f
private const val DIP = 22f

/**
 * The tab bar is the water's surface. The tab you're on floats at the surface; the other rests
 * just under the water: a little smaller, softer and dimmer, drawn toward the middle the way real
 * perspective would. Tapping it dips it first, as if its weight pushed it under, then it comes up
 * while the old one sinks and stays under, and the surface ripples where you tapped. Swiping
 * between the pages carries the icons along with your finger.
 */
@Composable
internal fun TabBar(
    selected: Tab,
    position: () -> Float, // 0 = Home, 1 = Saved; follows the pages while they move
    water: Water,
    modifier: Modifier,
    onSelect: (Tab) -> Unit,
) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val scope = rememberCoroutineScope()
    val depth = remember { List(2) { i -> Animatable(if (i == selected.ordinal) 0f else UNDER) } }
    val lit = remember { List(2) { i -> Animatable(if (i == selected.ordinal) 1f else 0f) } }
    var tapping by remember { mutableStateOf<Job?>(null) }
    var width by remember { mutableFloatStateOf(0f) }

    // Swipes (and anything else that turns the page) carry the icons with them, and the gap
    // between the two pages runs down into the bar like a stick dipped in water (F2).
    LaunchedEffect(Unit) {
        snapshotFlow { position() }.collect { pos ->
            if (width > 0f) {
                // Each page shrinks a little as it leaves (5% at most), about its own centre.
                val half = width / 2f
                val homeRight = -pos * width + half + half * (1f - 0.05f * pos.coerceIn(0f, 1f))
                val savedLeft = (1f - pos) * width + half - half * (1f - 0.05f * (1f - pos).coerceIn(0f, 1f))
                water.stick((homeRight + savedLeft) / 2f)
            }
            if (tapping?.isActive == true) return@collect
            for (i in 0..1) {
                val d = abs(pos - i).coerceIn(0f, 1f)
                depth[i].snapTo(UNDER * d)
                lit[i].snapTo(1f - d)
            }
        }
    }

    fun tap(t: Tab) {
        val to = t.ordinal
        val from = 1 - to
        val cx = width * (0.25f + 0.5f * to)
        val already = t == selected && depth[to].value < 1f
        with(density) {
            if (already) {
                // Already here: just a small touch on the water.
                water.ripple(cx, 2.5.dp.toPx(), 34.dp.toPx(), 11.dp.toPx(), 0.9f)
                onSelect(t)
                return
            }
            water.ripple(cx, 4.dp.toPx(), 40.dp.toPx(), 11.dp.toPx(), 1f)
        }
        tapping?.cancel()
        // The dip-and-rise owns the icons from here (set before the page starts to turn).
        tapping = scope.launch {
            launch {
                // The tapped one dips first, then comes up to the surface.
                depth[to].animateTo(DIP, tween(120, easing = Motion.inCubic))
                launch { lit[to].animateTo(1f, tween(140, easing = LinearEasing)) }
                depth[to].animateTo(0f, Motion.resurface())
            }
            launch {
                // The old one sinks a moment later and stays under.
                delay(50)
                launch { lit[from].animateTo(0f, tween(220, easing = LinearEasing)) }
                depth[from].animateTo(UNDER, tween(420, easing = Motion.inOutCubic))
            }
        }
        onSelect(t)
    }

    Column(
        modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width.toFloat(); water.size(width, density.density) }
            // The bar's background is the water; its top edge is the surface, and it ripples.
            .drawBehind {
                @Suppress("UNUSED_VARIABLE") val f = water.frame
                water.draw(this, p.chrome, p.separator, 0.5.dp.toPx(), p.accent)
            }
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp)) {
            TabItem("Home", Icons.Home, Icons.HomeFill, { depth[0].value }, { lit[0].value }, { width / 4f }, Modifier.weight(1f)) { tap(Tab.Home) }
            TabItem("Saved", Icons.Bookmark, Icons.BookmarkFill, { depth[1].value }, { lit[1].value }, { -width / 4f }, Modifier.weight(1f)) { tap(Tab.Saved) }
        }
        Spacer(Modifier.height(navBottom))
    }
}

/**
 * Depth faked in 2D. Going under, an icon gets smaller (further away), softer and dimmer (fading
 * into the water) and drawn toward the middle (perspective). Coming up reverses all of it.
 */
@Composable
private fun TabItem(
    label: String,
    icon: ImageVector,
    iconSelected: ImageVector,
    depth: () -> Float,
    lit: () -> Float,
    toMiddle: () -> Float, // px from this tab's centre to the bar's middle
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Column(
        modifier.fillMaxSize().clickable(
            remember { MutableInteractionSource() }, indication = null,
            role = Role.Tab, onClickLabel = label, onClick = onClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.graphicsLayer {
            val d = depth()
            val s = 100f / (100f + d)
            val deep = max(0f, d)
            translationX = toMiddle() * (1f - s) * 0.55f
            translationY = -(1f - s) * 14.dp.toPx()
            scaleX = s; scaleY = s
            alpha = 1f - (deep / 24f).coerceIn(0f, 1f) * 0.88f
            if (Build.VERSION.SDK_INT >= 31) {
                val r = deep * 0.065f * density
                renderEffect = if (r > 0.3f) BlurEffect(r, r, TileMode.Decal) else null
            }
        }) {
            // Outline and filled icons cross-fade, so the tab "fills in" as it comes up.
            Icon(icon, p.secondary, size = 25.dp, contentDescription = label,
                modifier = Modifier.graphicsLayer { alpha = 1f - lit() })
            Icon(iconSelected, p.accent, size = 25.dp,
                modifier = Modifier.graphicsLayer { alpha = lit() })
        }
        Spacer(Modifier.height(2.dp))
        Box(Modifier.graphicsLayer {
            val d = depth()
            val s = 100f / (100f + d)
            val deep = max(0f, d)
            translationX = toMiddle() * (1f - s) * 0.55f * 0.6f
            translationY = -(1f - s) * 14.dp.toPx() * 0.3f
            val ls = 1f - (1f - s) * 0.5f
            scaleX = ls; scaleY = ls
            alpha = 1f - (deep / 24f).coerceIn(0f, 1f) * 0.7f
        }) {
            val style = Type.caption.copy(fontSize = TextUnit(10.5f, TextUnitType.Sp))
            Text(label, style, color = p.secondary, modifier = Modifier.graphicsLayer { alpha = 1f - lit() })
            Text(label, style, color = p.accent, modifier = Modifier.graphicsLayer { alpha = lit() })
        }
    }
}
