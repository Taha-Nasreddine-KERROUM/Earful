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
 * can't interrupt you. It lives in the app's own files (not the cache folder, which Android may
 * empty while the app is closed), and is deleted as soon as it's no longer needed.
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
    /** Picks up a file kept from an earlier run, or throws its bytes away (n = 0). */
    internal fun restore(n: Long) = synchronized(lock) { written = n; lock.notifyAll() }
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

    /**
     * [keep]: tracks of an unfinished session (the app was closed with a video still open).
     * Their audio stays so you resume instantly, even offline; anything else is leftover: binned.
     */
    fun init(context: Context, keep: Set<String>) {
        dir = File(context.filesDir, "audio")
        dir.mkdirs()
        // Older versions kept it in the cache folder, which Android empties when space runs low:
        // a video left open could lose its audio while the app was closed. Bring it over once.
        File(context.cacheDir, "audio").takeIf { it.isDirectory }?.let { old ->
            old.listFiles()?.forEach { f ->
                if (f.name.substringBeforeLast('.') in keep) runCatching { f.renameTo(File(dir, f.name)) || run { f.copyTo(File(dir, f.name), true); f.delete() } }
            }
            old.deleteRecursively()
        }
        dir.listFiles()?.forEach { f ->
            if (f.name.substringBeforeLast('.') !in keep) f.delete()
        }
    }

    private fun metaFile(id: String) = File(dir, "$id.meta")

    private fun writeMeta(f: AudioFile, done: Boolean) = runCatching {
        metaFile(f.videoId).writeText("""{"len":${f.totalLength},"done":$done}""")
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
        // Kept from last time? Use what's there: a finished file needs no network at all.
        val meta = metaFile(videoId)
        if (f.file.exists() && meta.exists()) {
            val m = runCatching { org.json.JSONObject(meta.readText()) }.getOrNull()
            val len = m?.optLong("len", -1) ?: -1
            val have = f.file.length()
            if (len > 0 && have in 1..len) {
                f.totalLength = len
                f.restore(have)
                if (m?.optBoolean("done") == true && have == len) {
                    f.finish()
                    _progress.update { it + (videoId to 1f) }
                    return f
                }
            } else { f.file.delete(); meta.delete() }
        }
        _progress.update { it + (videoId to if (f.totalLength > 0) f.written.toFloat() / f.totalLength else 0f) }
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
        Session.clear()
    }

    private fun drop(f: AudioFile) {
        jobs.remove(f.videoId)?.cancel()
        f.drop()
        f.file.delete()
        metaFile(f.videoId).delete()
        _progress.update { it - f.videoId }
    }

    /** App going away with a video still open: stop downloading but keep every byte. */
    @Synchronized
    fun suspendAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        files.values.forEach { it.drop() }
        files.clear()
    }

    // ---- per-track work ----------------------------------------------------------------------

    private suspend fun CoroutineScope.fetch(f: AudioFile) {
        val audio = resolve(f) ?: return
        // Resuming a kept file only works if YouTube still serves byte-for-byte the same one.
        if (f.written > 0 && (audio.contentLength <= 0 || audio.contentLength != f.totalLength)) {
            f.restore(0); f.file.delete()
        }
        f.totalLength = audio.contentLength
        writeMeta(f, done = false)
        withContext(Dispatchers.Main) { onResolved?.invoke(audio) }
        download(f, audio.streamUrl) { YouTube.resolve(f.videoId, null) }
    }

    /** Looks the track up; waits out dropped connections, gives up only on real refusals. */
    private suspend fun CoroutineScope.resolve(f: AudioFile): ResolvedAudio? {
        var backoff = 1500L
        var hiccups = 0
        while (isActive) {
            try {
                return YouTube.resolve(f.videoId, null)
            } catch (e: IOException) {
                ensureActive()
                delay(backoff); backoff = (backoff * 2).coerceAtMost(15_000)
            } catch (t: Throwable) {
                ensureActive()
                val msg = friendly(t)
                // A real refusal (age, country, private, removed) is final. Anything else is often
                // a passing hiccup on YouTube's side: ask again a few times before giving up, so a
                // queued video doesn't fail in your pocket and stop the music.
                if (!permanent(t) && hiccups++ < 3) {
                    delay(backoff); backoff = (backoff * 2).coerceAtMost(15_000)
                    continue
                }
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
        var expiredRounds = 0
        var plainGet = false
        if (f.written == 0L) f.file.delete() // otherwise: carry on from where a previous run stopped
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
                    refreshes = 0 // links expire now and then over a long download: that's normal
                    if (got == 0L || (total <= 0 && got < end - pos + 1)) break // reached the end
                } catch (e: ExpiredLink) {
                    if (refreshes++ >= 3) {
                        // Refused several times in a row: wait a little and start over with a
                        // fresh link before giving up for good.
                        if (expiredRounds++ >= 3) { fail(f, "YouTube refused this one."); return }
                        delay(10_000L * expiredRounds)
                        refreshes = 1
                    }
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
        if (isActive) { f.finish(); writeMeta(f, done = true); _progress.update { it + (f.videoId to 1f) } }
    }

    private fun fail(f: AudioFile, msg: String) {
        failures[f.videoId] = msg
        f.fail(IOException(msg))
    }

    private fun permanent(t: Throwable): Boolean {
        val name = t::class.java.simpleName
        return "AgeRestricted" in name || "Geographic" in name || "Private" in name ||
            "ContentNotAvailable" in name || "Unavailable" in name || "Paid" in name || "Premium" in name
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
