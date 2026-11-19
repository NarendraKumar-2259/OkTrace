package io.github.narendrakumar2259.oktrace

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraceLoggerTest {

    private val server = MockWebServer()
    private val logged = mutableListOf<Pair<String, String>>()
    private val defaultLogger = OkTrace.logger

    private class Rewrite : Interceptor {
        override fun intercept(chain: Interceptor.Chain) = chain.proceed(
            chain.request().newBuilder()
                .url(chain.request().url.newBuilder().addPathSegment("v2").build())
                .post("{}".toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer secret-token")
                .build(),
        )
    }

    @BeforeTest fun setUp() {
        TraceStore.clear()
        OkTrace.logger = TraceLogSink { tag, message -> logged += tag to message }
        server.start()
    }

    @AfterTest fun tearDown() {
        OkTrace.logger = defaultLogger
        OkTrace.logTag = "OkTrace"
        OkTrace.clearRedactedHeaders()
        server.shutdown()
    }

    private fun call() {
        server.enqueue(MockResponse().setBody("ok"))
        OkHttpClient.Builder()
            .addInterceptor(OkTrace.interceptor())
            .addInterceptor(OkTrace.observe(Rewrite()))
            .build()
            .newCall(Request.Builder().url(server.url("/api")).build())
            .execute().close()
    }

    @Test
    fun `logger receives the tag set after the client was built`() {
        val interceptor = OkTrace.interceptor()
        OkTrace.logTag = "MyApp"
        server.enqueue(MockResponse())
        OkHttpClient.Builder().addInterceptor(interceptor).build()
            .newCall(Request.Builder().url(server.url("/")).build()).execute().close()

        assertEquals("MyApp", logged.single().first)
    }

    @Test
    fun `method and url changes are printed`() {
        call()
        val out = logged.single().second
        assertTrue("~ Method: GET → POST" in out, out)
        assertTrue("~ URL: ${server.url("/api/v2")}" in out, out)
        assertTrue("was: ${server.url("/api")}" in out, out)
    }

    @Test
    fun `redacted header values are hidden but still reported`() {
        OkTrace.redactHeader("authorization")
        call()
        val out = logged.single().second
        assertTrue("+ Authorization: ██" in out, out)
        assertFalse("secret-token" in out, out)
    }

    @Test
    fun `headers are printed when not redacted`() {
        call()
        assertTrue("+ Authorization: Bearer secret-token" in logged.single().second)
    }
}
