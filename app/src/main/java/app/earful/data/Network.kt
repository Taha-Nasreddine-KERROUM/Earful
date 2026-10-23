package app.earful.data

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/** One shared HTTP client for the whole app: one connection pool, one TLS session cache. */
object Network {
    /** Same desktop browser identity NewPipe uses; YouTube serves the cleanest pages to it. */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Few idle sockets, closed quickly: the radio can sleep sooner (battery).
        .connectionPool(ConnectionPool(4, 60, TimeUnit.SECONDS))
        .retryOnConnectionFailure(true)
        .build()
}

/** Bridges NewPipeExtractor's HTTP calls onto OkHttp. Requests go straight to youtube.com. */
object YouTubeHttp : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(request.url())
            .header("User-Agent", Network.USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        Network.client.newCall(builder.build()).execute().use { res ->
            if (res.code == 429) throw ReCaptchaException("YouTube asked for a captcha", request.url())
            return Response(
                res.code,
                res.message,
                res.headers.toMultimap(),
                res.body.string(),
                res.request.url.toString(),
            )
        }
    }
}
