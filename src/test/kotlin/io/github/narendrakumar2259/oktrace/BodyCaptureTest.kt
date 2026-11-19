package io.github.narendrakumar2259.oktrace

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSink
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyCaptureTest {

    private val server = MockWebServer()

    private class Passthrough : Interceptor {
        override fun intercept(chain: Interceptor.Chain) = chain.proceed(chain.request())
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(OkTrace.interceptor())
        .addInterceptor(OkTrace.observe(Passthrough()))
        .build()

    @BeforeTest fun setUp() {
        TraceStore.clear()
        server.start()
    }

    @AfterTest fun tearDown() = server.shutdown()

    @Test
    fun `one-shot request body is sent once and not captured`() {
        server.enqueue(MockResponse().setBody("ok"))
        var writes = 0
        val oneShot = object : RequestBody() {
            override fun contentType() = "text/plain".toMediaType()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                writes++
                check(writes == 1) { "one-shot body written $writes times" }
                sink.writeUtf8("hello")
            }
        }

        client.newCall(Request.Builder().url(server.url("/")).post(oneShot).build()).execute().use {
            assertEquals(200, it.code)
        }

        assertEquals(1, writes)
        assertEquals("hello", server.takeRequest().body.readUtf8())
        val span = TraceStore.entries.single().spans.single()
        assertEquals(UNREADABLE_BODY, span.requestBefore.bodyPreview)
    }

    @Test
    fun `large request body preview is capped but sent in full`() {
        server.enqueue(MockResponse().setBody("ok"))
        val payload = "a".repeat(1_000_000)

        client.newCall(
            Request.Builder().url(server.url("/")).post(payload.toRequestBody("text/plain".toMediaType())).build()
        ).execute().close()

        assertEquals(payload.length.toLong(), server.takeRequest().bodySize)
        val preview = TraceStore.entries.single().spans.single().requestBefore.bodyPreview
        assertEquals(4096, preview?.length)
    }

    @Test
    fun `event stream response is not previewed and does not block`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: 1\n\n")
                .throttleBody(1, 1, TimeUnit.SECONDS),
        )

        val start = System.nanoTime()
        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        response.close()

        assertTrue(elapsedMs < 2_000, "execute() took ${elapsedMs}ms; the body was read while tracing")
        assertNull(TraceStore.entries.single().finalResponse?.bodyPreview)
    }
}
