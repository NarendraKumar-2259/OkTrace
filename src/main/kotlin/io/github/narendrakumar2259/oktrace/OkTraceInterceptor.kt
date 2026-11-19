package io.github.narendrakumar2259.oktrace

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class OkTraceInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!OkTrace.enabled) return chain.proceed(chain.request())
        val request = chain.request()
        val entry = TraceEntry(url = request.url.toString(), method = request.method)

        TraceStore.startTrace(entry)

        val tagged = request.newBuilder()
            .tag(TraceId::class.java, TraceId(entry.id))
            .build()

        val start = System.nanoTime()
        return try {
            val response = chain.proceed(tagged)
            TraceStore.finishTrace(
                traceId    = entry.id,
                response   = response.toSnapshot(),
                error      = null,
                durationMs = (System.nanoTime() - start).nanosToMillis(),
                onFinished = { TraceLogger.log(OkTrace.logTag, it) },
            )
            response
        } catch (e: Exception) {
            TraceStore.finishTrace(
                traceId    = entry.id,
                response   = null,
                error      = e,
                durationMs = (System.nanoTime() - start).nanosToMillis(),
                onFinished = { TraceLogger.log(OkTrace.logTag, it) },
            )
            throw e
        }
    }
}

internal class ObservedInterceptor(
    private val delegate: Interceptor,
    private val name: String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        // No trace means OkTrace is off or its root interceptor is missing:
        // skip snapshots entirely so a disabled OkTrace costs nothing.
        val traceId = chain.request().tag(TraceId::class.java)?.value
            ?: return delegate.intercept(chain)

        val sequence = spanSequence.incrementAndGet()
        val before = chain.request().toSnapshot()
        val recorder = ProceedRecorder()
        val start = System.nanoTime()

        var response: Response? = null
        var error: Exception? = null
        try {
            response = delegate.intercept(ObservingChain(chain, recorder))
            return response
        } catch (e: Exception) {
            error = e
            throw e
        } finally {
            val durationNanos = System.nanoTime() - start
            val after = (recorder.forwardedRequest ?: chain.request()).toSnapshot()
            TraceStore.addSpan(traceId, InterceptorSpan(
                interceptorName = name,
                requestBefore   = before,
                requestAfter    = after,
                response        = response?.toSnapshot(),
                error           = error,
                durationMs      = durationNanos.nanosToMillis(),
                diff            = RequestDiff.diff(before, after),
                selfDurationMs  = (durationNanos - recorder.proceedNanos).coerceAtLeast(0).nanosToMillis(),
                sequence        = sequence,
            ))
        }
    }

    private companion object {
        val spanSequence = AtomicLong()
    }
}

/** What an observed interceptor sent down the chain, and how long that took. */
private class ProceedRecorder {
    var forwardedRequest: Request? = null
    var proceedNanos: Long = 0L
}

/**
 * Forwards everything to [chain] while recording calls to [proceed].
 * The `withXxxTimeout` variants stay wrapped, so an interceptor that changes
 * a timeout before proceeding is still observed.
 */
private class ObservingChain(
    private val chain: Interceptor.Chain,
    private val recorder: ProceedRecorder,
) : Interceptor.Chain {
    override fun request(): Request = chain.request()

    override fun proceed(request: Request): Response {
        recorder.forwardedRequest = request
        val start = System.nanoTime()
        try {
            return chain.proceed(request)
        } finally {
            recorder.proceedNanos += System.nanoTime() - start
        }
    }

    override fun connection(): Connection? = chain.connection()
    override fun call(): Call = chain.call()
    override fun connectTimeoutMillis(): Int = chain.connectTimeoutMillis()
    override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain =
        ObservingChain(chain.withConnectTimeout(timeout, unit), recorder)
    override fun readTimeoutMillis(): Int = chain.readTimeoutMillis()
    override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain =
        ObservingChain(chain.withReadTimeout(timeout, unit), recorder)
    override fun writeTimeoutMillis(): Int = chain.writeTimeoutMillis()
    override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain =
        ObservingChain(chain.withWriteTimeout(timeout, unit), recorder)
}

private fun Long.nanosToMillis(): Long = TimeUnit.NANOSECONDS.toMillis(this)

@JvmInline value class TraceId(val value: String)

internal fun Request.toSnapshot() = RequestSnapshot(
    url         = url.toString(),
    method      = method,
    headers     = headers.toMap(),
    bodyPreview = captureBodyPreview(),
)

internal fun Response.toSnapshot() = ResponseSnapshot(
    code        = code,
    message     = message,
    headers     = headers.toMap(),
    bodyPreview = captureBodyPreview(),
    contentType = body?.contentType()?.toString(),
)