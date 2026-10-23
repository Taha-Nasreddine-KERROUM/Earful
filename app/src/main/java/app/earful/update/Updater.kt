package app.earful.update

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
 * it builds a new APK and publishes it as a release. This class looks at that release (one tiny
 * request, at most every few hours, or right away when a video fails to load), downloads the
 * APK to the cache and hands it to Android's installer. Android always asks for one tap to
 * confirm an install — no app is allowed to update itself silently.
 */
object Updater {
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val enabled get() = BuildConfig.UPDATE_REPO.isNotBlank()

    /** Checks GitHub for a newer build. [force] skips the "every few hours" limit. */
    fun check(context: Context, force: Boolean = false) {
        if (!enabled) return
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Ready) return
        val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val since = now - prefs.getLong("last_check", 0)
        if ((!force && since < CHECK_EVERY_MS) || (force && since < 10 * 60 * 1000L)) {
            // Still remember an update we already found earlier.
            restore(prefs); return
        }
        prefs.edit().putLong("last_check", now).apply()
        scope.launch {
            runCatching { latest() }.onSuccess { found ->
                if (found != null && found.versionCode > BuildConfig.VERSION_CODE) {
                    prefs.edit().putInt("vc", found.versionCode).putString("url", found.url)
                        .putLong("size", found.sizeBytes).apply()
                    _state.value = found
                } else {
                    prefs.edit().remove("vc").apply()
                }
            }
        }
    }

    private fun restore(prefs: android.content.SharedPreferences) {
        val vc = prefs.getInt("vc", 0)
        val url = prefs.getString("url", null)
        if (vc > BuildConfig.VERSION_CODE && url != null && _state.value is UpdateState.Idle) {
            _state.value = UpdateState.Available(vc, url, prefs.getLong("size", 0))
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

    /** Downloads the update, then opens the installer. */
    fun downloadAndInstall(context: Context) {
        val s = _state.value
        if (s is UpdateState.Ready) { install(context, s.file); return }
        if (s !is UpdateState.Available) return
        val app = context.applicationContext
        _state.value = UpdateState.Downloading(0f)
        scope.launch {
            val dir = File(app.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val out = File(dir, "Earful-${s.versionCode}.apk")
            val ok = runCatching {
                Network.client.newCall(okhttp3.Request.Builder().url(s.url).build()).execute().use { res ->
                    if (!res.isSuccessful) error("HTTP ${res.code}")
                    val total = res.body.contentLength().takeIf { it > 0 } ?: s.sizeBytes
                    res.body.byteStream().use { input ->
                        out.outputStream().use { o ->
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
            }.isSuccess
            if (!ok) { out.delete(); _state.value = UpdateState.Failed; return@launch }
            _state.value = UpdateState.Ready(out)
            withContext(Dispatchers.Main) { install(app, out) }
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
        File(context.cacheDir, "updates").listFiles()?.forEach { f ->
            val vc = Regex("""Earful-(\d+)\.apk""").matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (vc <= BuildConfig.VERSION_CODE) f.delete()
        }
    }
}
