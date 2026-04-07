package io.github.narendrakumar2259.oktrace

import okhttp3.Interceptor

object OkTrace {

    /** Logcat tag. Filter by this in Android Studio. Default: "OkTrace" */
    var logTag: String = "OkTrace"

    /** Set to false to temporarily stop logging without rebuilding the client. */
    var enabled: Boolean = true

    /**
     * The root interceptor. Add this FIRST in your OkHttpClient builder.
     */
    fun interceptor(): Interceptor = OkTraceInterceptor(logTag)

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