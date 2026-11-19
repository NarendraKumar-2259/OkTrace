package io.github.narendrakumar2259.oktrace

import java.util.UUID

enum class TraceStatus { IN_FLIGHT, SUCCESS, FAILURE }

enum class DiffType { ADDED, REMOVED, CHANGED, UNCHANGED }

data class HeaderDiff(
    val key: String,
    val before: String?,
    val after: String?,
    val type: DiffType,
)

data class RequestSnapshot(
    val url: String,
    val method: String,
    val headers: Map<String, List<String>>,
    val bodyPreview: String?,
)

data class ResponseSnapshot(
    val code: Int,
    val message: String,
    val headers: Map<String, List<String>>,
    val bodyPreview: String?,
    val contentType: String?,
)

data class RequestDiffResult(
    val urlChanged: Boolean,
    val methodChanged: Boolean,
    val headerDiffs: List<HeaderDiff>,
    val bodyChanged: Boolean,
) {
    val hasChanges: Boolean
        get() = urlChanged || methodChanged || bodyChanged ||
                headerDiffs.any { it.type != DiffType.UNCHANGED }

    val changedHeaders: List<HeaderDiff>
        get() = headerDiffs.filter { it.type != DiffType.UNCHANGED }
}

data class InterceptorSpan(
    val interceptorName: String,
    val requestBefore: RequestSnapshot,
    val requestAfter: RequestSnapshot,
    val response: ResponseSnapshot?,
    val error: Throwable?,
    /** Time from entering this interceptor until it returned, including everything after it in the chain. */
    val durationMs: Long,
    val diff: RequestDiffResult,
    /** Time spent in this interceptor itself: [durationMs] minus the time spent inside `chain.proceed()`. */
    val selfDurationMs: Long = durationMs,
    /** Start order inside the trace; spans are kept sorted by it. */
    internal val sequence: Long = 0L,
)

data class TraceEntry(
    val id: String = UUID.randomUUID().toString(),
    val startedAt: Long = System.currentTimeMillis(),
    val url: String,
    val method: String,
    val spans: List<InterceptorSpan> = emptyList(),
    val finalResponse: ResponseSnapshot? = null,
    val error: Throwable? = null,
    val totalDurationMs: Long = 0L,
    val status: TraceStatus = TraceStatus.IN_FLIGHT,
)