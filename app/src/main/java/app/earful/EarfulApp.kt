package app.earful

import android.app.Application
import app.earful.data.Library
import app.earful.data.VideoShapes
import app.earful.data.Network
import app.earful.data.YouTube
import app.earful.playback.AudioCache
import app.earful.playback.Session
import app.earful.update.Updater
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okio.Path.Companion.toOkioPath

class EarfulApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        YouTube.init()
        Library.init(this)
        VideoShapes.init(this)
        Session.init(this)
        // Keeps the audio of a video you left open; deletes anything else left over.
        AudioCache.init(this, keep = Session.keepIds())
        Updater.cleanUp(this)
        Updater.cancelBackgroundChecks(this)
    }

    /** Thumbnails: small memory cache, 40 MB disk cap in the cache folder (cleared with the cache). */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Network.client })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.12).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("img").toOkioPath())
                    .maxSizeBytes(40L * 1024 * 1024)
                    .build()
            }
            .build()
}
