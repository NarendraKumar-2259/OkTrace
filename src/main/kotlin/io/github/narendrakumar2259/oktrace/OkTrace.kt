package io.github.narendrakumar2259.oktrace

import okhttp3.Interceptor
import java.util.TreeSet

/** Receives each finished trace as one formatted block of text. */
fun interface TraceLogSink {
    fun log(tag: String, message: String)
}

object OkTrace {

    /** Logcat tag. Filter by this in Android Studio. Default: "OkTrace" */
    @Volatile
    var logTag: String = "OkTrace"

    /** Set to false to temporarily stop logging without rebuilding the client. */
    @Volatile
    var enabled: Boolean = true

    /**
     * Where formatted traces go. The default prints to standard output, which
     * Android shows under the `System.out` tag. To use [logTag] in Logcat:
     *
     * ```kotlin
     * OkTrace.logger = TraceLogSink { tag, message -> Log.d(tag, message) }
     * ```
     */
    @Volatile
    var logger: TraceLogSink = TraceLogSink { _, message -> println(message) }

    private val redacted = TreeSet(String.CASE_INSENSITIVE_ORDER)

    /**
     * Hides the value of these headers in logged output, e.g.
     * `OkTrace.redactHeader("Authorization", "Cookie")`. Names are
     * case-insensitive. Changes are still detected, since the diff runs on
     * the real values; only the printed value is replaced.
     */
    fun redactHeader(vararg names: String) {
        synchronized(redacted) { redacted.addAll(names) }
    }

    /** Removes every name added with [redactHeader]. */
    fun clearRedactedHeaders() {
        synchronized(redacted) { redacted.clear() }
    }

    internal fun isRedacted(name: String): Boolean = synchronized(redacted) { name in redacted }

    /**
     * The root interceptor. Add this FIRST in your OkHttpClient builder.
     */
    fun interceptor(): Interceptor = OkTraceInterceptor()

    /**
     * Wraps an interceptor so OkTrace records its timing and mutations.
     *
     * Usage:
     * ```kotlin
     * OkHttpClient.Builder()
     *     .addInterceptor(OkTrace.interceptor())
     *     .addInterceptor(OkTrace.observe(AuthInterceptor()))
     *     .addInterceptor(OkTrace.observe(LoggingInterceptor()))
     *     .build()
     * ```
     */
    fun observe(
        interceptor: Interceptor,
        name: String = interceptor.javaClass.simpleName,
    ): Interceptor = ObservedInterceptor(interceptor, name)

    /** Access recorded traces programmatically. */
    val store: TraceStore get() = TraceStore
}
