package app.earful.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Listens to the sound as it's played (no microphone involved: it sits in the player's own audio
 * path) and passes it through untouched. It measures the bass, the middle and the treble 50
 * times a second, each measurement stamped with where it is in the video, for the little
 * "playing" bars in Up next.
 */
@OptIn(UnstableApi::class)
class LevelMeter : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        SoundLevels.analyze(inputBuffer, inputAudioFormat.channelCount)
        val out = replaceOutputBuffer(n)
        out.put(inputBuffer)
        out.flip()
    }

    // After every start or seek: where in the video the next sound is (µs).
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) =
        SoundLevels.restart(streamMetadata.positionOffsetUs, inputAudioFormat.sampleRate)

    override fun onReset() = SoundLevels.restart(0, 0)
}

/** The measurements, shared with the screen (same app process). */
object SoundLevels {
    private const val SIZE = 1024 // ~20 s of measurements
    private val time = LongArray(SIZE)
    private val bands = Array(3) { FloatArray(SIZE) }
    @Volatile private var count = 0

    // written only on the player's audio thread
    private var baseUs = 0L
    private var rate = 44_100
    private var frames = 0L
    private var lowState = 0f
    private var midState = 0f
    private var aLow = 0f
    private var aMid = 0f
    private var chunk = 882
    private var n = 0
    private val acc = DoubleArray(3)

    @Synchronized
    internal fun restart(positionUs: Long, sampleRate: Int) {
        baseUs = positionUs
        if (sampleRate > 0) rate = sampleRate
        frames = 0; n = 0; acc.fill(0.0); lowState = 0f; midState = 0f
        aLow = (1.0 - exp(-2.0 * PI * 180.0 / rate)).toFloat()  // below ~180 Hz: bass
        aMid = (1.0 - exp(-2.0 * PI * 2500.0 / rate)).toFloat() // up to ~2.5 kHz: middle
        chunk = (rate / 50).coerceAtLeast(1)
        count = 0
    }

    /** How many things on screen are showing the bars. With none, the sound isn't measured at
     *  all (only counted, to keep the times right): no work while the screen is off. */
    @Volatile var watchers = 0

    @Synchronized
    internal fun analyze(buf: ByteBuffer, channels: Int) {
        if (channels <= 0 || aLow == 0f) return
        if (watchers <= 0) {
            frames += buf.remaining() / (2 * channels)
            n = 0; acc.fill(0.0)
            return
        }
        val b = buf.duplicate().order(ByteOrder.nativeOrder())
        val frameBytes = 2 * channels
        while (b.remaining() >= frameBytes) {
            var sum = 0f
            for (c in 0 until channels) sum += b.short.toFloat()
            val x = sum / (channels * 32768f)
            lowState += aLow * (x - lowState)
            midState += aMid * (x - midState)
            val low = lowState
            val mid = midState - lowState
            val high = x - midState
            acc[0] += (low * low).toDouble(); acc[1] += (mid * mid).toDouble(); acc[2] += (high * high).toDouble()
            frames++
            if (++n == chunk) {
                val i = count % SIZE
                time[i] = baseUs + (frames - chunk) * 1_000_000L / rate
                for (k in 0..2) bands[k][i] = sqrt(acc[k] / n).toFloat()
                acc.fill(0.0); n = 0
                count++
            }
        }
    }

    /**
     * The bass, middle and treble (raw loudness) of the sound at [positionUs] in the video, into
     * [out]. False when there's nothing measured for that moment (yet).
     */
    @Synchronized
    fun at(positionUs: Long, out: FloatArray): Boolean {
        val c = count
        if (c == 0) return false
        val oldest = maxOf(0, c - SIZE)
        // the latest measurement at or before that moment (times only grow between restarts)
        var lo = oldest
        var hi = c - 1
        if (time[lo % SIZE] > positionUs) return false
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (time[mid % SIZE] <= positionUs) lo = mid else hi = mid - 1
        }
        val i = lo % SIZE
        if (positionUs - time[i] > 250_000L) return false // nothing that recent: don't guess
        for (k in 0..2) out[k] = bands[k][i]
        return true
    }
}
