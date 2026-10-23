package app.earful

import android.app.Application
import app.earful.data.Library
import app.earful.data.Network
import app.earful.data.YouTube
import app.earful.playback.AudioCache
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
        AudioCache.init(this) // also deletes any audio left over from a previous run
        Updater.cleanUp(this)
        Updater.check(this)
        // A video failing in a way that smells like "YouTube changed": look for a fix right now.
        AudioCache.onReaderOutdated = { Updater.check(this, force = true) }
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
