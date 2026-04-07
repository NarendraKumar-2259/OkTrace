package io.github.narendrakumar2259.oktrace

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

internal class OkTraceInterceptor(
    private val logTag: String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val entry = TraceEntry(url = request.url.toString(), method = request.method)

        TraceStore.startTrace(entry)

        val tagged = request.newBuilder()
            .tag(TraceId::class.java, TraceId(entry.id))
            .build()

        val start = System.currentTimeMillis()
        return try {
            val response = chain.proceed(tagged)
            TraceStore.finishTrace(
                traceId    = entry.id,
                response   = response.toSnapshot(),
                error      = null,
                durationMs = System.currentTimeMillis() - start,
                onFinished = { TraceLogger.log(logTag, it) },
            )
            response
        } catch (e: Exception) {
            TraceStore.finishTrace(
                traceId    = entry.id,
                response   = null,
                error      = e,
                durationMs = System.currentTimeMillis() - start,
                onFinished = { TraceLogger.log(logTag, it) },
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
        val traceId = chain.request().tag(TraceId::class.java)?.value
        val before = chain.request().toSnapshot()
        val start = System.currentTimeMillis()

        var forwardedRequest: Request? = null

        val observingChain = object : Interceptor.Chain {
            override fun request(): Request = chain.request()

            override fun proceed(request: Request): Response {
                forwardedRequest = request
                return chain.proceed(request)
            }

            override fun connection(): Connection? = chain.connection()
            override fun call(): Call = chain.call()
            override fun connectTimeoutMillis(): Int = chain.connectTimeoutMillis()
            override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = chain.withConnectTimeout(timeout, unit)
            override fun readTimeoutMillis(): Int = chain.readTimeoutMillis()
            override fun withReadTimeout(timeout: Int, unit: TimeUnit) = chain.withReadTimeout(timeout, unit)
            override fun writeTimeoutMillis(): Int = chain.writeTimeoutMillis()
            override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = chain.withWriteTimeout(timeout, unit)
        }

        return try {
            val response = delegate.intercept(observingChain)
            val after = (forwardedRequest ?: chain.request()).toSnapshot()

            traceId?.let {
                TraceStore.addSpan(it, InterceptorSpan(
                    interceptorName = name,
                    requestBefore   = before,
                    requestAfter    = after,
                    response        = response.toSnapshot(),
                    error           = null,
                    durationMs      = System.currentTimeMillis() - start,
                    diff            = RequestDiff.diff(before, after),
                ))
            }
            response
        } catch (e: Exception) {
            val after = (forwardedRequest ?: chain.request()).toSnapshot()
            traceId?.let {
                TraceStore.addSpan(it, InterceptorSpan(
                    interceptorName = name,
                    requestBefore   = before,
                    requestAfter    = after,
                    response        = null,
                    error           = e,
                    durationMs      = System.currentTimeMillis() - start,
                    diff            = RequestDiff.diff(before, after),
                ))
            }
            throw e
        }
    }
}

@JvmInline value class TraceId(val value: String)

internal fun Request.toSnapshot() = RequestSnapshot(
    url         = url.toString(),
    method      = method,
    headers     = headers.toMap(),
    bodyPreview = null,
)

internal fun Response.toSnapshot() = ResponseSnapshot(
    code        = code,
    message     = message,
    headers     = headers.toMap(),
    bodyPreview = captureBodyPreview(),
    contentType = body?.contentType()?.toString(),
)