package app.earful.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.common.PlaybackException
import java.io.RandomAccessFile

/**
 * Feeds the player from the file [AudioCache] is filling. If the player asks for bytes that
 * haven't arrived yet (a seek far ahead, or a slow network) it simply waits for them — the user
 * sees a brief "buffering", never an error.
 */
@UnstableApi
class CachedAudioDataSource : BaseDataSource(/* isNetwork= */ false) {
    private var raf: RandomAccessFile? = null
    private var audio: AudioFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val id = dataSpec.uri.lastPathSegment
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        // Starts the download if it isn't running (e.g. jumping back to a passed track).
        val f = AudioCache.acquire(id)
        transferInitializing(dataSpec)
        // Wait until the track is looked up and its first bytes are on disk.
        f.awaitAvailable(0)
        audio = f
        raf = RandomAccessFile(f.file, "r").also { it.seek(dataSpec.position) }
        position = dataSpec.position
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            f.totalLength > 0 -> (f.totalLength - position).coerceAtLeast(0)
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val f = audio ?: return C.RESULT_END_OF_INPUT
        val available = f.awaitAvailable(position)
        if (available <= 0) return C.RESULT_END_OF_INPUT
        var toRead = minOf(length.toLong(), available)
        if (remaining != C.LENGTH_UNSET.toLong()) toRead = minOf(toRead, remaining)
        val n = raf!!.read(buffer, offset, toRead.toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try { raf?.close() } finally {
            raf = null; audio = null
            if (opened) { opened = false; transferEnded() }
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = CachedAudioDataSource()
    }

    companion object {
        fun uriFor(videoId: String): Uri = Uri.parse("earful://audio/$videoId")
    }
}
