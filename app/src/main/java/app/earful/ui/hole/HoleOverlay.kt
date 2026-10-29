package app.earful.ui.hole

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import app.earful.ui.player.SkipSpin
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Under the app while the hole is open: deep space (nearly black, a few sharp stars), so what
 * the hole has eaten leaves stars behind. Drawn once into a picture, then reused.
 */
@Composable
fun StarField(modifier: Modifier = Modifier) {
    if (!Hole.isActive) return
    val pic = remember { arrayOfNulls<ImageBitmap>(1) }
    Canvas(modifier.fillMaxSize()) {
        val w = size.width.roundToInt().coerceAtLeast(1); val h = size.height.roundToInt().coerceAtLeast(1)
        val bm = pic[0]?.takeIf { it.width == w && it.height == h } ?: stars(w, h, density).also { pic[0] = it }
        drawImage(bm)
    }
    DisposableEffect(Unit) { onDispose { pic[0] = null } }
}

private fun stars(w: Int, h: Int, density: Float): ImageBitmap {
    val bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bm)
    c.drawColor(android.graphics.Color.rgb(1, 1, 3))
    val rnd = Random(7)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    // a faint haze, then three layers of stars: most tiny and faint, a rare few brighter
    p.shader = android.graphics.RadialGradient(w * 0.7f, h * 0.3f, max(w, h) * 0.7f,
        intArrayOf(android.graphics.Color.argb(26, 40, 26, 70), android.graphics.Color.argb(0, 0, 0, 0)), null, android.graphics.Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    p.shader = null
    val unit = w / 270f
    for (layer in 0 until 3) {
        val cell = (16f + layer * layer * 14f) * unit
        val cols = (w / cell).toInt() + 1; val rows = (h / cell).toInt() + 1
        for (j in 0 until rows) for (i in 0 until cols) {
            if (rnd.nextFloat() < 0.72f) continue
            val mag = rnd.nextFloat().pow(5f) * (1.2f + layer * 1.1f)
            if (mag < 0.02f) continue
            val x = (i + 0.1f + rnd.nextFloat() * 0.8f) * cell
            val y = (j + 0.1f + rnd.nextFloat() * 0.8f) * cell
            val warm = rnd.nextFloat()
            val r = (255 * (1f - 0.2f * warm)).toInt(); val g = (255 * (0.88f)).toInt(); val b = (255 * (0.76f + 0.24f * warm)).toInt()
            val a = (min(1f, mag) * 255).toInt()
            p.color = android.graphics.Color.argb(a, r, g, b)
            c.drawCircle(x, y, (0.45f + 0.5f * min(1f, mag)) * density * 0.6f, p)
            if (mag > 0.5f) {
                p.color = android.graphics.Color.argb((mag * mag * 0.06f * 255).toInt().coerceAtMost(60), r, g, b)
                c.drawCircle(x, y, 3f * density, p)
            }
        }
    }
    return bm.asImageBitmap()
}

/**
 * The black hole, in front of the app: what's falling in (unbent, swallowed at the edge of the
 * black), what's thrown back out, the "10" orbiting it, the flash, and — on Androids without the
 * light-bending ([HoleLens]) — the hole and its disc themselves. While it's open it takes every
 * touch; a tap on the hole counts as a tap on the button.
 */
@Composable
fun HoleOverlay(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    DisposableEffect(scope) {
        Hole.scope = scope
        onDispose { Hole.abort(); Hole.scope = null }
    }
    // the light-bending is worked out ahead, as the phone starts to tremble
    LaunchedEffect(Unit) {
        snapshotFlow { Hole.shakeX != 0f || Hole.isActive }.first { it }
        HoleLens.prepare(scope)
        // and dropped again a while after the hole has closed
        while (true) {
            snapshotFlow { Hole.isActive }.first { !it }
            kotlinx.coroutines.delay(20_000)
            if (!Hole.isActive && Hole.shakeX == 0f) { HoleLens.release(); snapshotFlow { Hole.shakeX != 0f || Hole.isActive }.first { it }; HoleLens.prepare(scope) }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            snapshotFlow { Hole.isActive }.first { it }
            var last = -1L
            while (Hole.isActive) {
                withFrameNanos { n ->
                    val dt = if (last < 0) 0f else ((n - last) / 1e9f).coerceAtMost(0.05f)
                    last = n
                    Hole.step(dt)
                }
            }
        }
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val red = remember {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                0.285f, 0.56f, 0.105f, 0f, 0f,
                0.075f, 0.147f, 0.028f, 0f, 0f,
                0.036f, 0.071f, 0.013f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )))
        }
    }
    val verts = remember { FloatArray(2 * 21 * 21) }

    Box(
        modifier.fillMaxSize()
            .onSizeChanged {
                Hole.screen = Rect(0f, 0f, it.width.toFloat(), it.height.toFloat())
                Hole.k = it.width / 270f
            }
            // (only while it's open: a layer that listens to touches takes them from everything under it)
            .then(if (Hole.isActive) Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!Hole.isActive) return@awaitEachGesture
                    down.consume()
                    val near = hypot(down.position.x - Hole.cx, down.position.y - Hole.cy) < max(44f * density.density, Hole.shadowR() * 1.2f)
                    if (near && Hole.phase != Hole.Phase.Fade) Hole.spin?.onTap?.invoke()
                    // the rest of the touch goes nowhere
                    while (true) {
                        val e = awaitPointerEvent()
                        e.changes.forEach { it.consume() }
                        if (e.changes.all { !it.pressed }) break
                    }
                }
            } else Modifier)
    ) {
        // the hole and its disc, drawn flat (only where the light-bending isn't available)
        Canvas(Modifier.fillMaxSize()) {
            Hole.frame
            if (Hole.isActive && !HoleLens.ready) flatHole(this)
        }
        // what falls in and what's thrown out, swallowed at the edge of the black
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }) {
            Hole.frame
            if (!Hole.isActive) return@Canvas
            val moving = Hole.moving()
            drawIntoCanvas { cv ->
                val nc = cv.nativeCanvas
                for (p in moving) drawPiece(nc, p, paint, red, verts)
            }
            val sh = Hole.shadowR() / 1.04f
            if (sh > 1f) drawCircle(
                Brush.radialGradient(
                    0f to Color.Black, (1.3f / 2.3f) to Color.Black, 1f to Color.Transparent,
                    center = Offset(Hole.cx, Hole.cy), radius = sh * 2.3f,
                ),
                radius = sh * 2.3f, center = Offset(Hole.cx, Hole.cy), blendMode = BlendMode.DstOut,
            )
        }
        // the button's own arrow and its "10" around the hole, and the flash
        Canvas(Modifier.fillMaxSize()) {
            Hole.frame
            if (!Hole.isActive) return@Canvas
            val sp = Hole.spin as? SkipSpin ?: return@Canvas
            val c = Offset(Hole.cx, Hole.cy)
            val w = 38f * density.density
            val kk = Hole.k
            when (Hole.phase) {
                Hole.Phase.Suck -> {
                    if (Hole.form < 1f) sp.drawPlain(this, c, w * (1f + Hole.form * 1.2f), Color.White, 1f - Hole.form)
                    val orb = Hole.smooth(0.3f, 1.2f, Hole.formT)
                    val a = Hole.orb
                    val rr = max(18f * kk, Hole.rs * 6.4f) * orb
                    val ox = cos(a) * rr; val oy = sin(a) * rr * 0.24f
                    val cs = cos(-0.1f); val sn = sin(-0.1f)
                    val app = cos(a) * Hole.dir
                    sp.drawTen(
                        this, Offset(c.x + ox * cs - oy * sn, c.y + ox * sn + oy * cs), w, Color.White,
                        scale = 1f + 0.25f * app * orb,
                        glow = if (app > 0f) Color(0xE6AAC8FF) else Color(0xE6FFAA78), glowR = 8f * orb * density.density,
                    )
                }
                Hole.Phase.Eject, Hole.Phase.Fade -> {
                    val q = Hole.ejT - 0.35f
                    if (q >= 0f && Hole.phase == Hole.Phase.Eject) sp.drawTen(
                        this, Offset(c.x + Hole.tenX, c.y + Hole.tenY), w, Color.White,
                        rot = Hole.tenRz, scale = 0.2f + 0.8f * Hole.smooth(0f, 0.3f, q),
                    )
                    if (Hole.ejT > 2.4f || Hole.phase == Hole.Phase.Fade) {
                        sp.drawPlain(this, c, w, Color.White, if (Hole.phase == Hole.Phase.Fade) 1f else Hole.smooth(2.4f, 3.2f, Hole.ejT))
                        if (Hole.phase == Hole.Phase.Fade) sp.drawTen(this, c, w, Color.White)
                    }
                }
                Hole.Phase.Idle -> Unit
            }
            if (Hole.flash >= 0f) {
                val u = Hole.flash
                val r = (60f + u * 420f) * kk
                drawRect(Brush.radialGradient(
                    listOf(Color(1f, 0.98f, 0.94f, 0.95f * (1f - u) * (1f - u)), Color(1f, 0.94f, 0.86f, 0f)),
                    center = c, radius = r,
                ))
            }
            sp.drawCounter(this, c)
        }
    }
}

/** One thing falling in (bent along its fall), coming back out, or tumbling home. */
private fun DrawScope.drawPiece(nc: android.graphics.Canvas, p: HolePiece, paint: Paint, red: Paint, verts: FloatArray) {
    val bm = p.bitmap ?: return
    val b = p.bounds
    val k = Hole.k
    val f = p.fly
    if (f != null) {
        val q = Hole.ejT - f.start
        if (q < 0f) return
        val s = Hole.smooth(0f, 0.3f, q)
        val sx = s * max(0.12f, abs(cos(Math.toRadians(f.ry.toDouble())).toFloat()))
        val sy = s * max(0.12f, abs(cos(Math.toRadians(f.rx.toDouble())).toFloat()))
        if (sx <= 0.001f || sy <= 0.001f) return
        paint.alpha = ((q * 10f).coerceIn(0f, 1f) * 255).toInt()
        nc.save()
        nc.translate(b.center.x + f.x, b.center.y + f.y)
        nc.rotate(f.rz)
        nc.scale(sx, sy)
        nc.drawBitmap(bm, null, android.graphics.RectF(-b.width / 2, -b.height / 2, b.width / 2, b.height / 2), paint)
        nc.restore()
        return
    }
    val e = p.fallE
    if (e <= 0f) {
        // back in its place, waiting for the pages to finish pouring back out
        if (p.back) { paint.alpha = 255; nc.drawBitmap(bm, null, android.graphics.RectF(b.left, b.top, b.right, b.bottom), paint) }
        return
    }
    if (p.page) {
        // a page going down the drain: the middle first (it's nearest), twisting as it pours in
        mesh(nc, bm, b, 10, 20, e, Hole.T_PAGE, Hole.K_PAGE, paint, 1f, verts)
        return
    }
    // spaghetti: stretched into a stream that winds in; at the edge it reddens, dims and fades
    val tf = p.tf
    val cols = (b.width / k / 16f).roundToInt().coerceIn(3, 16)
    val rows = (b.height / k / 16f).roundToInt().coerceIn(2, 10)
    val redA = Hole.smooth(0.45f, 1f, e / tf)
    val fade = 1f - ((e - tf * 0.95f) / 1.1f).coerceIn(0f, 1f)
    if (fade <= 0.003f) return
    mesh(nc, bm, b, cols, rows, e, Hole.T_PIECE, Hole.K_PIECE, paint, fade * (1f - redA * 0.9f), verts)
    if (redA > 0.02f) mesh(nc, bm, b, cols, rows, e, Hole.T_PIECE, Hole.K_PIECE, red, fade * redA, verts)
}

private fun mesh(
    nc: android.graphics.Canvas, bm: android.graphics.Bitmap, b: Rect, cols: Int, rows: Int,
    e: Float, t0: Float, kExp: Float, paint: Paint, alpha: Float, verts: FloatArray,
) {
    if (alpha <= 0.003f) return
    var i = 0
    for (j in 0..rows) for (c in 0..cols) {
        Hole.infall(b.left + b.width * c / cols, b.top + b.height * j / rows, e, t0, kExp, verts, i)
        i += 2
    }
    paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
    nc.drawBitmapMesh(bm, cols, rows, verts, 0, null, 0, paint)
}

/** The hole drawn flat: a glow, the far side of the disc, the black, a thin ring of light, the near side. */
private fun flatHole(d: DrawScope) = with(d) {
    val c = Offset(Hole.cx, Hole.cy)
    val form = Hole.form
    if (form <= 0.001f || Hole.rs <= 0.05f) return@with
    val rs = Hole.rs
    val sh = rs * 2.6f
    val rin = 3f * rs; val rout = (3f + 10f * form) * rs
    val flare = 1f + 0.55f * Hole.flare
    drawCircle(Brush.radialGradient(listOf(Color(1f, 0.78f, 0.5f, 0.22f * form * flare), Color.Transparent), c, sh * 3.2f), sh * 3.2f, c)
    val ringW = rout - rin
    val rx = (rin + rout) / 2f
    val ry = rx * 0.24f
    val disc = Brush.horizontalGradient(
        listOf(Color(0.75f, 0.2f, 0.07f, form), Color(1f, 0.62f, 0.26f, form), Color(1f, 0.97f, 0.9f, form), Color(1f, 0.62f, 0.26f, form), Color(0.75f, 0.2f, 0.07f, form)),
        startX = c.x - rout, endX = c.x + rout,
    )
    rotate(-5.7f, c) {
        // the far half of the disc
        drawArc(disc, 180f, 180f, false, Offset(c.x - rx, c.y - ry), Size(rx * 2, ry * 2), alpha = 0.85f * flare.coerceAtMost(1f), style = Stroke(ringW * 0.24f))
    }
    // its far side, bent up over the top of the black
    drawArc(disc, 200f, 140f, false, Offset(c.x - sh * 1.25f, c.y - sh * 1.3f), Size(sh * 2.5f, sh * 2.2f), alpha = 0.55f, style = Stroke(rs * 0.9f))
    drawCircle(Color.Black, sh, c)
    drawCircle(Color(1f, 0.85f, 0.65f, 0.55f * form), sh * 1.02f, c, style = Stroke(rs * 0.25f))
    rotate(-5.7f, c) {
        // the near half, in front of the black
        drawArc(disc, 0f, 180f, false, Offset(c.x - rx, c.y - ry), Size(rx * 2, ry * 2), alpha = 0.95f, style = Stroke(ringW * 0.28f))
    }
}
