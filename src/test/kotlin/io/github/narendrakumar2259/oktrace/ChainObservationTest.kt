package io.github.narendrakumar2259.oktrace

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChainObservationTest {

    private val server = MockWebServer()

    private class AddHeader(private val name: String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain) =
            chain.proceed(chain.request().newBuilder().header(name, "1").build())
    }

    private class Slow(private val ms: Long) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            Thread.sleep(ms)
            return chain.proceed(chain.request())
        }
    }

    /** Changes a timeout, then adds a header on the chain it got back. */
    private class TimeoutThenHeader : Interceptor {
        override fun intercept(chain: Interceptor.Chain) =
            chain.withReadTimeout(5, TimeUnit.SECONDS)
                .let { it.proceed(it.request().newBuilder().header("X-T", "1").build()) }
    }

    @BeforeTest fun setUp() {
        TraceStore.clear()
        OkTrace.enabled = true
        server.start()
    }

    @AfterTest fun tearDown() {
        OkTrace.enabled = true
        server.shutdown()
    }

    private fun call(vararg interceptors: Interceptor) {
        val builder = OkHttpClient.Builder().addInterceptor(OkTrace.interceptor())
        interceptors.forEach { builder.addInterceptor(it) }
        builder.build().newCall(Request.Builder().url(server.url("/")).build()).execute().close()
    }

    @Test
    fun `spans are recorded in chain order`() {
        server.enqueue(MockResponse())
        call(
            OkTrace.observe(AddHeader("X-A"), "A"),
            OkTrace.observe(AddHeader("X-B"), "B"),
            OkTrace.observe(AddHeader("X-C"), "C"),
        )
        assertEquals(listOf("A", "B", "C"), TraceStore.entries.single().spans.map { it.interceptorName })
    }

    @Test
    fun `self time excludes the rest of the chain`() {
        server.enqueue(MockResponse().setBodyDelay(300, TimeUnit.MILLISECONDS).setBody("x"))
        call(OkTrace.observe(Slow(0), "Fast"), OkTrace.observe(Slow(200), "Slow"))

        val (fast, slow) = TraceStore.entries.single().spans
        assertTrue(fast.durationMs >= 200, "total time includes downstream work: ${fast.durationMs}")
        assertTrue(fast.selfDurationMs < 100, "Fast's own time was ${fast.selfDurationMs}ms")
        assertTrue(slow.selfDurationMs >= 200, "Slow's own time was ${slow.selfDurationMs}ms")
    }

    @Test
    fun `changes made after withReadTimeout are still observed`() {
        server.enqueue(MockResponse())
        call(OkTrace.observe(TimeoutThenHeader(), "T"))
        val diff = TraceStore.entries.single().spans.single().diff
        assertEquals(listOf("X-T"), diff.changedHeaders.map { it.key })
    }

    @Test
    fun `disabled OkTrace records nothing`() {
        OkTrace.enabled = false
        server.enqueue(MockResponse())
        call(OkTrace.observe(AddHeader("X-A"), "A"))
        assertTrue(TraceStore.entries.isEmpty())
    }
}
