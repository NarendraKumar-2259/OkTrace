package io.github.narendrakumar2259.oktrace

import java.util.concurrent.ConcurrentHashMap

object TraceStore {
    private val inFlight = ConcurrentHashMap<String, TraceEntry>()
    private val completed = mutableListOf<TraceEntry>()
    var maxEntries = 100
    val entries: List<TraceEntry> get() = synchronized(completed) { completed.toList() }

    fun startTrace(entry: TraceEntry) {
        inFlight[entry.id] = entry
    }

    fun addSpan(traceId: String, span: InterceptorSpan) {
        val entry = inFlight[traceId] ?: return  // find the entry
        inFlight[traceId] = entry.copy(spans = entry.spans + span)  // update it
    }

    fun finishTrace(
        traceId: String,
        response: ResponseSnapshot?,
        error: Throwable?,
        durationMs: Long,
        onFinished: (TraceEntry) -> Unit,
    ) {
        val entry = inFlight.remove(traceId) ?: return
        val finished = entry.copy(
            finalResponse = response,
            error = error,
            totalDurationMs = durationMs,
            status = if (error != null) TraceStatus.FAILURE else TraceStatus.SUCCESS
        )
        synchronized(completed) {
            completed.add(finished)
            while (completed.size > maxEntries) completed.removeAt(0)
        }

        onFinished(finished)
    }

    fun clear() {
        inFlight.clear()
        synchronized(completed) { completed.clear() }
    }
}

