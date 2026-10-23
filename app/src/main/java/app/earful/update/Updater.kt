package app.earful.update

import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.earful.BuildConfig
import app.earful.data.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val versionCode: Int, val url: String, val sizeBytes: Long) : UpdateState
    data class Downloading(val fraction: Float) : UpdateState
    data class Ready(val file: File) : UpdateState
    data object Failed : UpdateState
}

/**
 * Keeps Earful working when YouTube changes something.
 *
 * Every day, the GitHub workflow in this project checks whether NewPipe has shipped a fix; if so
 * it builds a new APK and publishes it as a release. This class looks for that release when you
 * open the app and once an hour while it's open (never in the background), and **downloads it by
 * itself** (a ~3 MB file) so it's sitting ready. Home then shows "Update ready · Install": one tap,
 * then Android's own confirmation, which no app is allowed to skip.
 */
object Updater {
    private const val JOB_ID = 4201 // only used to cancel the old background check

    /** True only while Earful is on screen. Nothing is ever checked while it isn't. */
    @Volatile var foreground = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = Mutex()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val enabled get() = BuildConfig.UPDATE_REPO.isNotBlank()

    private fun dir(context: Context) = File(context.cacheDir, "updates").apply { mkdirs() }

    /** A finished download from earlier (also from the background job) is "Ready" right away. */
    private fun findReady(context: Context): File? =
        dir(context).listFiles()?.mapNotNull { f ->
            val vc = Regex("""Earful-(\d+)\.apk""").matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()
            if (vc != null && vc > BuildConfig.VERSION_CODE) vc to f else null
        }?.maxByOrNull { it.first }?.second

    /**
     * Called once when the app is opened fresh, then once an hour while it stays open on screen —
     * never in the background. Everything happens off the main thread: the app stays fully usable.
     */
    fun check(context: Context) {
        if (!enabled || !foreground) return
        val app = context.applicationContext
        scope.launch {
            findReady(app)?.let { if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.Ready(it) }
            checkAndDownload(app)
        }
    }

    /** Looks for a newer build and, if there is one, downloads it. Safe to call from anywhere. */
    suspend fun checkAndDownload(context: Context) {
        if (!enabled) return
        busy.withLock {
            val app = context.applicationContext
            app.getSharedPreferences("update", Context.MODE_PRIVATE).edit()
                .putLong("last_check", System.currentTimeMillis()).apply()
            val found = runCatching { latest() }.getOrNull() ?: return
            if (found.versionCode <= BuildConfig.VERSION_CODE) return
            val ready = findReady(app)
            if (ready != null && ready.name == "Earful-${found.versionCode}.apk") {
                _state.value = UpdateState.Ready(ready); return
            }
            download(app, found)
        }
    }

    private fun latest(): UpdateState.Available? {
        val req = okhttp3.Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Earful-updater")
            .build()
        Network.client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val assets = JSONObject(res.body.string()).optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val m = Regex("""Earful-(\d+)\.apk""").matchEntire(a.optString("name")) ?: continue
                return UpdateState.Available(m.groupValues[1].toInt(), a.getString("browser_download_url"), a.optLong("size"))
            }
            return null
        }
    }

    /** Downloads to a temporary name first, so a half-finished file is never offered. */
    private fun download(app: Context, s: UpdateState.Available) {
        val dir = dir(app)
        dir.listFiles()?.forEach { it.delete() } // only ever one update on disk
        val part = File(dir, "Earful-${s.versionCode}.apk.part")
        val out = File(dir, "Earful-${s.versionCode}.apk")
        _state.value = UpdateState.Downloading(0f)
        val ok = runCatching {
            Network.client.newCall(okhttp3.Request.Builder().url(s.url).build()).execute().use { res ->
                if (!res.isSuccessful) error("HTTP ${res.code}")
                val total = res.body.contentLength().takeIf { it > 0 } ?: s.sizeBytes
                res.body.byteStream().use { input ->
                    part.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            o.write(buf, 0, n); done += n
                            if (total > 0) _state.value = UpdateState.Downloading((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            part.renameTo(out) || error("rename failed")
        }.getOrElse { false }
        if (!ok) { part.delete(); _state.value = UpdateState.Failed; return }
        _state.value = UpdateState.Ready(out)
    }

    /** The "Install" button. Normally the file is already downloaded, so this is instant. */
    fun downloadAndInstall(context: Context) {
        val app = context.applicationContext
        when (val s = _state.value) {
            is UpdateState.Ready -> install(context, s.file)
            is UpdateState.Available -> scope.launch {
                busy.withLock { download(app, s) }
                (_state.value as? UpdateState.Ready)?.let { r -> withContext(Dispatchers.Main) { install(app, r.file) } }
            }
            UpdateState.Failed -> { _state.value = UpdateState.Idle; scope.launch { checkAndDownload(app) } }
            else -> Unit
        }
    }

    private fun install(context: Context, file: File) {
        // First time only: Android asks the user to allow Earful to install updates.
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return // they come back and tap "Install" again
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun dismissFailure() { if (_state.value is UpdateState.Failed) _state.value = UpdateState.Idle }

    /** After an update installs, the old APK in the cache is useless: delete it. */
    fun cleanUp(context: Context) {
        dir(context).listFiles()?.forEach { f ->
            val vc = Regex("""Earful-(\d+)\.apk(\.part)?""").matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (vc <= BuildConfig.VERSION_CODE || f.name.endsWith(".part")) f.delete()
        }
    }

    /**
     * There are no background checks. Earlier test builds scheduled one; make sure it's gone.
     */
    fun cancelBackgroundChecks(context: Context) {
        runCatching { context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
    }
}

