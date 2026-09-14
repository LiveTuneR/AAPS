package app.aaps.plugins.sync.tidepool.comm

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import com.google.common.truth.Truth.assertThat
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.argumentCaptor

/**
 * Request payloads and user-specific paths must never enter diagnostic logs.
 */
class InfoInterceptorTest {

    private val aapsLogger: AAPSLogger = mock()
    private val sut = InfoInterceptor(aapsLogger)

    private fun chainFor(request: Request): Pair<Interceptor.Chain, Response> {
        val response = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("".toResponseBody(null))
            .build()
        val chain: Interceptor.Chain = mock()
        whenever(chain.request()).thenReturn(request)
        whenever(chain.proceed(request)).thenReturn(response)
        return chain to response
    }

    @Test
    fun `only metadata is logged and the request goes on`() {
        val body = """[{"type":"cbg"}]"""
        val request = Request.Builder()
            .url("https://api.tidepool.org/v1/datasets/1/data")
            .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        val (chain, response) = chainFor(request)

        assertThat(sut.intercept(chain)).isEqualTo(response)
        val log = argumentCaptor<String>()
        verify(aapsLogger).debug(org.mockito.kotlin.eq(LTag.TIDEPOOL), log.capture())
        assertThat(log.firstValue).contains("responseCode=200")
        assertThat(log.firstValue).doesNotContain(body)
        assertThat(log.firstValue).doesNotContain("datasets/1")
    }

    @Test
    fun `request without a body logs zero length`() {
        val request = Request.Builder().url("https://api.tidepool.org/v1/datasets").build()
        val (chain, response) = chainFor(request)

        assertThat(sut.intercept(chain)).isEqualTo(response)
        val log = argumentCaptor<String>()
        verify(aapsLogger).debug(org.mockito.kotlin.eq(LTag.TIDEPOOL), log.capture())
        assertThat(log.firstValue).contains("contentLength=0")
    }
}
