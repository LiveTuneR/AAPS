package app.aaps.plugins.sync.tidepool.comm

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class InfoInterceptor(val aapsLogger: AAPSLogger) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val start = System.nanoTime()
        // Paths contain dataset/user identifiers. Emit no URL, headers or body.
        val response = chain.proceed(request)
        val contentLength = try { request.body?.contentLength() ?: 0 } catch (_: IOException) { -1 }
        aapsLogger.debug(LTag.TIDEPOOL, "HTTP method=${request.method} contentLength=$contentLength responseCode=${response.code} durationMs=${(System.nanoTime() - start) / 1_000_000}")
        return response
    }
}
