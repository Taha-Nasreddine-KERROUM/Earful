package app.earful.ui.hole

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Half
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The light bending round the hole (Android 13+): the app behind it, the stars and the glowing
 * disc are seen through a real black hole's gravity.
 *
 * The paths of light near the hole are traced once, on the CPU, into small tables (a ray for
 * every spot within 26 hole-radii: where it lands on the app behind, whether it falls in, where
 * it crosses the disc). Every frame the GPU only looks those up, so the bending costs about as
 * much as a blur, even on a cheap phone. Far from the hole the textbook weak-bending formula is
 * used. Nothing is computed until the hole is about to open (+1700 s), and it's forgotten after.
 */
object HoleLens {
    internal const val N = 224 // the square tables' size
    internal const val RN = 2048 // the radial table's
    private const val MAPR = 26f // the tables cover this many hole-radii around it
    private const val DS = 16f // how far behind the hole the app sits (in hole radii)

    @Volatile private var maps: Array<Bitmap>? = null
    @Volatile private var computing = false
    private var shader: Any? = null // RuntimeShader (kept as Any so older Androids never load the class)
    private var failed = false

    val supported get() = Build.VERSION.SDK_INT >= 33 && !failed

    /** Starts tracing the light in the background, if it isn't done yet. */
    fun prepare(scope: CoroutineScope) {
        if (!supported || maps != null || computing) return
        computing = true
        scope.launch(Dispatchers.Default) {
            maps = runCatching {
                val t = traceRaw()
                arrayOf(bitmap(t[0], RN, 2)) + t.drop(1).map { bitmap(it, N, N) }
            }.getOrNull()
            if (maps == null) failed = true
            computing = false
        }
    }

    /** Lets go of the tables (about 1.6 MB) once the hole has closed. */
    fun release() {
        if (computing) return
        maps = null
        shader = null
    }

    val ready get() = supported && maps != null

    /** The bending for this frame, or null (no hole, not ready, or an older Android). */
    fun effect(w: Float, h: Float): RenderEffect? {
        if (!ready || Build.VERSION.SDK_INT < 33) return null
        return try { effect33(w, h) } catch (t: Throwable) { failed = true; null }
    }

    @RequiresApi(33)
    private fun effect33(w: Float, h: Float): RenderEffect? {
        val m = maps ?: return null
        val s = (shader as? RuntimeShader) ?: RuntimeShader(AGSL).also { sh ->
            // read exactly as stored (no colour handling, no smoothing: the shader blends them itself)
            listOf("mapT", "mapH1a", "mapH1b", "mapH2a", "mapH2b").forEachIndexed { i, name ->
                sh.setInputBuffer(name, BitmapShader(m[i], Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    filterMode = BitmapShader.FILTER_MODE_NEAREST
                })
            }
            shader = sh
        }
        val wave = Hole.waveAge
        s.setFloatUniform("uSize", w, h)
        s.setFloatUniform("uC", Hole.cx, Hole.cy)
        s.setFloatUniform("uRs", Hole.rs)
        s.setFloatUniform("uForm", Hole.form)
        s.setFloatUniform("uTime", Hole.t)
        s.setFloatUniform("uSpin", Hole.dir)
        s.setFloatUniform("uFlare", Hole.flare)
        s.setFloatUniform("uK", Hole.k)
        s.setFloatUniform("uN", N.toFloat())
        s.setFloatUniform("uRN", RN.toFloat())
        s.setFloatUniform("uWave", Hole.cx, Hole.cy, max(0f, wave), if (wave >= 0f) 0.55f else 0f)
        return android.graphics.RenderEffect.createRuntimeShaderEffect(s, "content").asComposeRenderEffect()
    }

    // ---- tracing the light ------------------------------------------------------------------------

    /**
     * The tables, three channels each, every value already scaled into 0..1:
     *  0. along the radius (RN × 2): where a ray that far out lands (as a deflection, in two
     *     parts for precision), its glow; and whether it falls in;
     *  1–4. over the square around the hole (N × N), the first and the second place each ray
     *     crosses the disc: where on the disc, how its light is shifted, and whether it does.
     */
    internal fun traceRaw(): List<FloatArray> {
        val radial = FloatArray(RN * 2 * 3)
        for (i in 0 until RN) {
            val bx = i.toFloat() / (RN - 1) * MAPR
            val r = ray(bx, 0f, null)
            val o = i * 3
            val dev = ((r.sx - bx) / 128f + 0.5f).coerceIn(0f, 0.9999f)
            // in two parts (whole 255ths, then the rest) so it survives any storage precision
            val hi = kotlin.math.floor(dev * 255f) / 255f
            radial[o] = hi; radial[o + 1] = ((dev - hi) * 255f).coerceIn(0f, 1f); radial[o + 2] = r.glow.coerceIn(0f, 1f)
            radial[RN * 3 + o] = if (r.caught) 1f else 0f
        }
        val h1a = FloatArray(N * N * 3); val h1b = FloatArray(N * N * 3)
        val h2a = FloatArray(N * N * 3); val h2b = FloatArray(N * N * 3)
        val hit = FloatArray(8)
        for (j in 0 until N) for (i in 0 until N) {
            val bx = ((i + 0.5f) / N * 2f - 1f) * MAPR
            val by = ((j + 0.5f) / N * 2f - 1f) * MAPR
            val r = ray(bx, by, hit)
            val k = (j * N + i) * 3
            fun put(a: FloatArray, b: FloatArray, o: Int, has: Boolean) {
                a[k] = if (has) (hit[o] / 32f + 0.5f).coerceIn(0f, 1f) else 0.5f
                a[k + 1] = if (has) (hit[o + 1] / 32f + 0.5f).coerceIn(0f, 1f) else 0.5f
                a[k + 2] = if (has) (hit[o + 2] / 4f).coerceIn(0f, 1f) else 0.25f
                b[k] = if (has) (hit[o + 3] / 4f).coerceIn(0f, 1f) else 0.25f
                b[k + 1] = if (has) 1f else 0f
            }
            put(h1a, h1b, 0, r.hits >= 1)
            put(h2a, h2b, 4, r.hits >= 2)
        }
        return listOf(radial, h1a, h1b, h2a, h2b)
    }

    private class Ray(val sx: Float, val sy: Float, val caught: Boolean, val glow: Float, val hits: Int)

    // the disc, seen almost edge-on, a little from above: its normal and two directions in it
    private val nrm: FloatArray = run {
        val x = -0.10f; val y = 1f; val z = 0.24f; val l = sqrt(x * x + y * y + z * z)
        floatArrayOf(x / l, y / l, z / l)
    }
    private val e1: FloatArray = run {
        val x = nrm[1]; val y = -nrm[0]; val l = sqrt(x * x + y * y)
        floatArrayOf(x / l, y / l, 0f)
    }
    private val e2: FloatArray = floatArrayOf(
        nrm[1] * e1[2] - nrm[2] * e1[1], nrm[2] * e1[0] - nrm[0] * e1[2], nrm[0] * e1[1] - nrm[1] * e1[0],
    )

    /**
     * Follows one ray of light from the viewer, starting [bx], [by] hole-radii from the middle
     * (screen directions), the way the design's shader did it: where it ends up on the app behind
     * the hole, whether it falls in, how close it came, and (into [hit], if given) where it
     * crossed the disc, up to twice: x, y on the disc, the light's shift for either spin.
     */
    private fun ray(bx: Float, by: Float, hit: FloatArray?): Ray {
        val nX = nrm[0]; val nY = nrm[1]; val nZ = nrm[2]
        var px = bx; var py = -by; var pz = 18f
        var vx = 0f; var vy = 0f; var vz = -1f
        val h2 = bx * bx + by * by
        var rmin = 99f
        var caught = false
        var hits = 0
        for (step in 0 until 140) {
            val r = sqrt(px * px + py * py + pz * pz)
            rmin = min(rmin, r)
            val dt = (0.07f * r).coerceIn(0.025f, 0.9f)
            val ox = px; val oy = py; val oz = pz
            val r5 = r * r * r * r * r
            val g = -1.5f * h2 / r5 * dt
            vx += g * px; vy += g * py; vz += g * pz
            px += vx * dt; py += vy * dt; pz += vz * dt
            if (hit != null && hits < 2) {
                val s0 = ox * nX + oy * nY + oz * nZ
                val s1 = px * nX + py * nY + pz * nZ
                if (s0 * s1 < 0f) {
                    val t = s0 / (s0 - s1)
                    val hx = ox + (px - ox) * t; val hy = oy + (py - oy) * t; val hz = oz + (pz - oz) * t
                    val rr = sqrt(hx * hx + hy * hy + hz * hz)
                    if (rr in 2.5f..13.5f) {
                        // its light is shifted: brighter and bluer where the disc turns towards you
                        val cxp = nY * hz - nZ * hy; val cyp = nZ * hx - nX * hz; val czp = nX * hy - nY * hx
                        val cl = sqrt(cxp * cxp + cyp * cyp + czp * czp).coerceAtLeast(1e-6f)
                        val vl = sqrt(vx * vx + vy * vy + vz * vz).coerceAtLeast(1e-6f)
                        val dot = (cxp * -vx + cyp * -vy + czp * -vz) / (cl * vl)
                        val v = sqrt(0.5f / rr)
                        val gam = 1f / sqrt(1f - v * v)
                        val grav = sqrt(max(0.05f, 1f - 1f / rr))
                        val o = hits * 4
                        hit[o] = hx * e1[0] + hy * e1[1] + hz * e1[2]
                        hit[o + 1] = hx * e2[0] + hy * e2[1] + hz * e2[2]
                        hit[o + 2] = grav / (gam * (1f - v * dot))
                        hit[o + 3] = grav / (gam * (1f + v * dot))
                        hits++
                    }
                }
            }
            if (r < 1.02f) { caught = true; break }
            if (pz < -DS) break
        }
        var sx = 0f; var sy = 0f
        if (!caught) {
            if (vz < -0.05f) {
                val t = (-DS - pz) / vz
                sx = px + vx * t; sy = -(py + vy * t)
            } else {
                val l = sqrt(vx * vx + vy * vy).coerceAtLeast(1e-4f)
                sx = vx / l * 60f; sy = -vy / l * 60f
            }
        }
        return Ray(sx, sy, caught, if (caught) 0f else exp(-(rmin - 1.5f) * 2.6f) * 0.32f, hits)
    }

    private fun bitmap(src: FloatArray, w: Int, h: Int): Bitmap {
        val buf = ByteBuffer.allocateDirect(w * h * 8).order(ByteOrder.nativeOrder())
        val sb = buf.asShortBuffer()
        val one = Half.toHalf(1f)
        for (p in 0 until w * h) {
            for (ch in 0 until 3) sb.put(Half.toHalf(src[p * 3 + ch]))
            sb.put(one)
        }
        buf.rewind()
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.RGBA_F16)
        bm.copyPixelsFromBuffer(buf)
        return bm
    }

    /** The bending, the disc and the glow, per pixel (AGSL). */
    /**
     * The n-th crossing of the disc, blended from the four nearest rays that make it (each
     * weighted by whether it does). Written out once per crossing: Android 13's shaders can't
     * take a table as a parameter.
     */
    private fun crossing(n: Int): String {
        fun tap(dx: Int, dy: Int, w: String) = """
    { half4 a = mapH${n}a.eval(i0 + float2(${dx}.5, ${dy}.5)); half4 q = mapH${n}b.eval(i0 + float2(${dx}.5, ${dy}.5));
      float wt = $w; float v = float(q.y) * wt;
      s += float3(float(a.x), float(a.y), float(a.z)) * v; fm += float(q.x) * v; vs += v; ws += wt; }"""
        return """
float4 crossing$n(float2 t, float4 acc) {
    float2 i0 = floor(t);
    float2 fr = t - i0;
    float3 s = float3(0.0); float fm = 0.0; float vs = 0.0; float ws = 0.0;
    ${tap(0, 0, "(1.0 - fr.x) * (1.0 - fr.y)")}
    ${tap(1, 0, "fr.x * (1.0 - fr.y)")}
    ${tap(0, 1, "(1.0 - fr.x) * fr.y")}
    ${tap(1, 1, "fr.x * fr.y")}
    if (vs < 0.0001) return acc;
    float2 h = (s.xy / vs - 0.5) * 32.0;
    float fp = s.z / vs * 4.0;
    float fmm = fm / vs * 4.0;
    return disc(h, uSpin > 0.0 ? fp : fmm, vs / max(ws, 0.0001), acc);
}
"""
    }

    internal val AGSL = """
uniform shader content;
uniform shader mapT;
uniform shader mapH1a;
uniform shader mapH1b;
uniform shader mapH2a;
uniform shader mapH2b;
uniform float2 uSize;
uniform float2 uC;
uniform float uRs;
uniform float uForm;
uniform float uTime;
uniform float uSpin;
uniform float uFlare;
uniform float uK;
uniform float uN;
uniform float uRN;
uniform float4 uWave;

float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
float noise(float2 p) {
    float2 i = floor(p); float2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), f.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), f.x), f.y);
}
float fbm(float2 p) { float v = 0.0; float a = 0.5; for (int i = 0; i < 5; i++) { v += a * noise(p); p *= 2.03; a *= 0.5; } return v; }

half4 ui(float2 p) {
    if (p.x < 0.0 || p.y < 0.0 || p.x > uSize.x || p.y > uSize.y) return half4(0.0, 0.0, 0.0, 1.0);
    return content.eval(p);
}

float4 disc(float2 h, float f, float valid, float4 acc) {
    float rr = length(h);
    float rin = 3.0;
    float rout = 3.0 + 10.0 * uForm;
    if (valid < 0.01 || rr <= rin || rr >= rout || acc.a >= 0.995) return acc;
    float x = (rr - rin) / (rout - rin);
    float phi = atan(h.y, h.x);
    float om = uSpin * 1.6 / pow(rr, 1.5);
    float ph = phi - uTime * om * 2.2;
    float turb = fbm(float2(cos(ph), sin(ph)) * 2.6 + float2(rr * 0.9, 0.0)) * 0.85 + 0.3 * noise(float2(rr * 3.0, ph * 5.0));
    float3 hot = mix(float3(1.0, 0.97, 0.9), float3(1.0, 0.62, 0.26), smoothstep(0.0, 0.45, x));
    hot = mix(hot, float3(0.75, 0.2, 0.07), smoothstep(0.4, 1.0, x));
    float I = (pow(1.0 - x, 1.5) * 1.45 + 0.12) * (0.55 + 0.9 * turb);
    I *= clamp(pow(f, 1.35), 0.25, 2.2);
    hot = mix(hot, hot * float3(0.75, 0.88, 1.35), clamp(f - 1.0, 0.0, 1.0));
    hot = mix(hot, hot * float3(1.2, 0.7, 0.5), clamp(1.0 - f, 0.0, 1.0));
    I *= 1.0 + 0.55 * uFlare;
    float a = clamp(I * 0.9, 0.0, 0.97) * smoothstep(rin, rin + 0.35, rr) * smoothstep(rout, rout - 2.0, rr) * uForm * valid;
    acc.rgb += (1.0 - acc.a) * hot * I * a;
    acc.a += (1.0 - acc.a) * a;
    return acc;
}

${crossing(1)}
${crossing(2)}
half4 main(float2 px) {
    // the gravity wave when it opens
    if (uWave.w > 0.0) {
        float2 d = px - uWave.xy; float r = length(d) + 0.001; float front = uWave.z * 330.0 * uK;
        float env = exp(-pow((r - front) / (26.0 * uK), 2.0)) * uWave.w * (1.0 - uWave.z / 1.2);
        px += d / r * sin((r - front) * 0.22 / uK) * 6.0 * uK * env;
    }
    if (uRs < 0.05) return ui(px);
    float2 b = (px - uC) / uRs;
    float R = length(b);
    float2 src;
    float caught = 0.0;
    float glow = 0.0;
    float4 acc = float4(0.0);
    if (R > 26.0) {
        // far out: bending that fades as 1/distance, continuing the table's last value
        half4 e = mapT.eval(float2(uRN - 0.5, 0.5));
        float edge = (floor(float(e.x) * 255.0 + 0.5) / 255.0 + float(e.y) / 255.0 - 0.5) * 128.0;
        src = uC + (b + b / R * (edge * 26.0 / R)) * uRs;
    } else {
        // along the radius: where it lands, whether it falls in, its glow
        float x = R / 26.0 * (uRN - 1.0);
        float i0 = floor(x);
        float fr = x - i0;
        half4 a0 = mapT.eval(float2(i0 + 0.5, 0.5));
        half4 a1 = mapT.eval(float2(min(i0 + 1.0, uRN - 1.0) + 0.5, 0.5));
        float d0 = floor(float(a0.x) * 255.0 + 0.5) / 255.0 + float(a0.y) / 255.0;
        float d1 = floor(float(a1.x) * 255.0 + 0.5) / 255.0 + float(a1.y) / 255.0;
        float dev = (mix(d0, d1, fr) - 0.5) * 128.0;
        float2 dir = R > 0.0001 ? b / R : float2(1.0, 0.0);
        src = uC + dir * (R + dev) * uRs;
        glow = mix(float(a0.z), float(a1.z), fr) * uForm;
        caught = mix(float(mapT.eval(float2(i0 + 0.5, 1.5)).x), float(mapT.eval(float2(min(i0 + 1.0, uRN - 1.0) + 0.5, 1.5)).x), fr);
        // the disc: where these rays cross it, first and second time
        float2 t = (b / 26.0 * 0.5 + 0.5) * uN - 0.5;
        acc = crossing1(t, acc);
        acc = crossing2(t, acc);
    }
    float3 back = float3(0.0);
    if (caught < 0.999) back = float3(ui(src).rgb) * (1.0 - caught);
    float3 dc = 1.0 - exp(-acc.rgb * 1.1);
    float3 col = back * (1.0 - acc.a) + dc;
    col += float3(1.0, 0.78, 0.5) * glow * (1.0 - acc.a * 0.6);
    return half4(half3(col), 1.0);
}
"""
}
