package app.earful.playback

import android.content.Context
import app.earful.data.Network
import app.earful.data.ResolvedAudio
import app.earful.data.YouTube
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap

/**
 * One audio file being pulled down in full.
 *
 * Playback reads from this file while it grows, so sound starts within a second, and once the
 * download finishes the network is no longer needed at all — a tunnel or a dropped connection
 * can't interrupt you. It lives in the app's cache folder: "Clear cache" wipes it.
 */
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
class AudioFile(val videoId: String, val file: File) {
    private val lock = Object()

    @Volatile var totalLength = -1L; internal set
    @Volatile var written = 0L; private set
    @Volatile var complete = false; private set
    @Volatile var fatal: IOException? = null; private set

    /** Completes when this file is fully downloaded, failed, or dropped. */
    internal val done = CompletableDeferred<Unit>()

    /** Blocks until at least one byte past [position] exists. Returns 0 at end of file. */
    fun awaitAvailable(position: Long): Long {
        synchronized(lock) {
            while (true) {
                val w = written
                if (w > position) return w - position
                if (complete) return 0
                fatal?.let { throw it }
                try {
                    lock.wait(400)
                } catch (e: InterruptedException) {
                    throw InterruptedIOException("seek or stop")
                }
            }
        }
    }

    internal fun advance(by: Long) = synchronized(lock) { written += by; lock.notifyAll() }
    internal fun finish() = synchronized(lock) {
        if (totalLength <= 0) totalLength = written
        complete = true; lock.notifyAll()
        done.complete(Unit)
    }
    internal fun fail(e: IOException) = synchronized(lock) { fatal = e; lock.notifyAll(); done.complete(Unit) }
    internal fun drop() { done.complete(Unit) }
}

/**
 * Keeps at most two tracks on the phone: the one playing and the one after it.
 * The next one only starts downloading once the current one is complete (so the current one
 * gets all the bandwidth), and anything you've moved past is deleted straight away.
 */
object AudioCache {
    /** 1 MB per request: small enough to resume quickly after a drop, big enough to be cheap. */
    private const val CHUNK = 1L shl 20
    private val POST_BODY = byteArrayOf(0x78, 0) // what YouTube's own players send

    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val files = LinkedHashMap<String, AudioFile>()
    private val jobs = HashMap<String, Job>()

    /** 0..1 per video id — how much of each kept track is safely on the phone. */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    /** Human-readable reason a track couldn't be loaded, by video id. */
    val failures = ConcurrentHashMap<String, String>()

    /** Called when YouTube answers in a way the reader doesn't understand (YouTube changed). */
    @Volatile var onReaderOutdated: (() -> Unit)? = null

    /** Called on the main thread once a track's details (title, stream, suggestions) are known. */
    @Volatile var onResolved: ((ResolvedAudio) -> Unit)? = null

    fun init(context: Context) {
        dir = File(context.cacheDir, "audio")
        // A fresh process never has anything playing: whatever is here is leftover. Bin it.
        dir.deleteRecursively()
        dir.mkdirs()
    }

    @Synchronized
    fun get(videoId: String): AudioFile? = files[videoId]

    /**
     * Returns the file for [videoId], starting it if needed. A file started with [after] waits
     * until that one has finished before using the network.
     */
    @Synchronized
    fun acquire(videoId: String, after: AudioFile? = null): AudioFile {
        // A track that failed stays failed (so the player's automatic retries don't hammer
        // YouTube) until the user asks again — see [forget].
        files[videoId]?.let { return it }
        dir.mkdirs()
        val f = AudioFile(videoId, File(dir, "$videoId.audio"))
        files[videoId] = f
        failures.remove(videoId)
        _progress.update { it + (videoId to 0f) }
        jobs[videoId] = scope.launch {
            after?.done?.await()
            fetch(f)
        }
        return f
    }

    /**
     * Keeps exactly [ids] (current first, then next) and deletes everything else.
     * Passed tracks lose their audio here but stay in the queue.
     */
    @Synchronized
    fun retain(ids: List<String>) {
        val keep = ids.distinct().take(2)
        files.keys.filter { it !in keep }.forEach { id -> files.remove(id)?.let { drop(it) } }
        var previous: AudioFile? = null
        keep.forEach { id -> previous = acquire(id, after = previous) }
    }

    /** Drops one track's file and failure so the next request starts it fresh ("Try again"). */
    @Synchronized
    fun forget(videoId: String) {
        files.remove(videoId)?.let { drop(it) }
        failures.remove(videoId)
    }

    /** Stops and deletes everything. Called when the player is closed. */
    @Synchronized
    fun releaseAll() {
        files.values.toList().forEach { drop(it) }
        files.clear()
        failures.clear()
        _progress.value = emptyMap()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun drop(f: AudioFile) {
        jobs.remove(f.videoId)?.cancel()
        f.drop()
        f.file.delete()
        _progress.update { it - f.videoId }
    }

    // ---- per-track work ----------------------------------------------------------------------

    private suspend fun CoroutineScope.fetch(f: AudioFile) {
        val audio = resolve(f) ?: return
        f.totalLength = audio.contentLength
        withContext(Dispatchers.Main) { onResolved?.invoke(audio) }
        download(f, audio.streamUrl) { YouTube.resolve(f.videoId, null) }
    }

    /** Looks the track up; waits out dropped connections, gives up only on real refusals. */
    private suspend fun CoroutineScope.resolve(f: AudioFile): ResolvedAudio? {
        var backoff = 1500L
        while (isActive) {
            try {
                return YouTube.resolve(f.videoId, null)
            } catch (e: IOException) {
                ensureActive()
                delay(backoff); backoff = (backoff * 2).coerceAtMost(15_000)
            } catch (t: Throwable) {
                ensureActive()
                val msg = friendly(t)
                val name = t::class.java.simpleName
                if ("Parsing" in name || "Extraction" in name || msg == "Couldn't load this one.") onReaderOutdated?.invoke()
                failures[f.videoId] = msg
                f.fail(IOException(msg))
                return null
            }
        }
        return null
    }

    private suspend fun CoroutineScope.download(f: AudioFile, firstUrl: String, refresh: suspend () -> ResolvedAudio) {
        var url = firstUrl
        var requestNo = 0
        var backoff = 1000L
        var refreshes = 0
        var plainGet = false
        f.file.delete()
        FileOutputStream(f.file, true).use { out ->
            while (isActive) {
                val pos = f.written
                val total = f.totalLength
                if (total in 1..pos) break
                val end = if (total > 0) minOf(pos + CHUNK, total) - 1 else pos + CHUNK - 1
                try {
                    val got = fetchRange(url, requestNo++, pos, end, out, f, plainGet)
                    if (got == 0L && total > 0) throw IOException("empty response")
                    backoff = 1000L
                    if (got == 0L || (total <= 0 && got < end - pos + 1)) break // reached the end
                } catch (e: ExpiredLink) {
                    if (refreshes++ >= 3) { fail(f, "YouTube refused this one."); return }
                    if (refreshes >= 2) plainGet = !plainGet
                    val fresh = runCatching { refresh() }.getOrNull()
                    if (fresh != null) {
                        // Only continue if it's byte-for-byte the same file we started.
                        if (f.totalLength > 0 && fresh.contentLength > 0 && fresh.contentLength != f.totalLength) {
                            fail(f, "This video changed on YouTube. Try again."); return
                        }
                        url = fresh.streamUrl
                    }
                } catch (e: IOException) {
                    ensureActive()
                    // No signal? Keep trying gently until it comes back; playback just waits.
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(15_000)
                }
            }
        }
        if (isActive) { f.finish(); _progress.update { it + (f.videoId to 1f) } }
    }

    private fun fail(f: AudioFile, msg: String) {
        failures[f.videoId] = msg
        f.fail(IOException(msg))
    }

    private fun friendly(t: Throwable): String {
        val name = t::class.java.simpleName
        return when {
            "AgeRestricted" in name -> "This video is age‑restricted on YouTube."
            "Geographic" in name -> "This video isn't available in your country."
            "Private" in name -> "This video is private."
            "ContentNotAvailable" in name || "Unavailable" in name -> "This video isn't available."
            "ReCaptcha" in name -> "YouTube is rate‑limiting. Try again in a minute."
            else -> "Couldn't load this one."
        }
    }

    private class ExpiredLink : IOException()

    /**
     * Same request shape YouTube's own player (and NewPipe's) uses for audio:
     * POST "x\0", a Range header and an increasing `rn` counter. If a link is refused we
     * fetch a fresh one, and on the second refusal fall back to a plain GET.
     */
    private fun fetchRange(url: String, rn: Int, start: Long, end: Long, out: FileOutputStream, f: AudioFile, plainGet: Boolean): Long {
        val req = okhttp3.Request.Builder()
            .url("$url&rn=$rn")
            .apply { if (plainGet) get() else post(POST_BODY.toRequestBody()) }
            .header("Range", "bytes=$start-$end")
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .header("User-Agent",
                if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) YoutubeParsingHelper.getVisionOsUserAgent(null)
                else Network.USER_AGENT)
            .apply {
                if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
                    header("Origin", "https://www.youtube.com")
                    header("Referer", "https://www.youtube.com/")
                }
            }
            .build()
        Network.client.newCall(req).execute().use { res ->
            if (res.code == 403 || res.code == 410) throw ExpiredLink()
            if (res.code == 416) return 0 // asked past the end: we're done
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val src = res.body.byteStream()
            // A server that ignores Range sends the whole file: skip what we already have.
            if (res.code == 200 && start > 0) {
                var toSkip = start
                while (toSkip > 0) { val k = src.skip(toSkip); if (k <= 0) throw IOException("short body"); toSkip -= k }
            }
            val wanted = end - start + 1
            val buf = ByteArray(64 * 1024)
            var total = 0L
            var lastReport = 0f
            while (total < wanted) {
                val n = src.read(buf, 0, minOf(buf.size.toLong(), wanted - total).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
                f.advance(n.toLong())
                val len = f.totalLength
                if (len > 0) {
                    val frac = (f.written.toFloat() / len).coerceIn(0f, 1f)
                    if (frac - lastReport >= 0.01f) { lastReport = frac; _progress.update { it + (f.videoId to frac) } }
                }
            }
            out.flush()
            return total
        }
    }
}
