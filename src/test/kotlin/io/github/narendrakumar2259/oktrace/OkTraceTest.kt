package io.github.narendrakumar2259.oktrace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestDiffTest {

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun snapshot(
        url: String = "https://api.example.com",
        method: String = "GET",
        headers: Map<String, List<String>> = emptyMap(),
        body: String? = null,
    ) = RequestSnapshot(url, method, headers, body)

    // ── No changes ───────────────────────────────────────────────────────

    @Test
    fun `identical snapshots have no changes`() {
        val s = snapshot()
        assertFalse(RequestDiff.diff(s, s).hasChanges)
    }

    // ── URL changes ──────────────────────────────────────────────────────

    @Test
    fun `url change is detected`() {
        val before = snapshot(url = "https://api.example.com")
        val after  = snapshot(url = "https://api.example.com/v2")
        val diff   = RequestDiff.diff(before, after)
        assertTrue(diff.urlChanged)
        assertTrue(diff.hasChanges)
    }

    // ── Method changes ───────────────────────────────────────────────────

    @Test
    fun `method change is detected`() {
        val before = snapshot(method = "GET")
        val after  = snapshot(method = "POST")
        val diff   = RequestDiff.diff(before, after)
        assertTrue(diff.methodChanged)
        assertTrue(diff.hasChanges)
    }

    // ── Header changes ───────────────────────────────────────────────────

    @Test
    fun `added header is detected`() {
        val before = snapshot(headers = emptyMap())
        val after  = snapshot(headers = mapOf("Authorization" to listOf("Bearer token")))
        val diff   = RequestDiff.diff(before, after)
        assertTrue(diff.hasChanges)
        assertEquals(DiffType.ADDED, diff.headerDiffs.first { it.key == "Authorization" }.type)
    }

    @Test
    fun `removed header is detected`() {
        val before = snapshot(headers = mapOf("Authorization" to listOf("Bearer token")))
        val after  = snapshot(headers = emptyMap())
        val diff   = RequestDiff.diff(before, after)
        assertTrue(diff.hasChanges)
        assertEquals(DiffType.REMOVED, diff.headerDiffs.first { it.key == "Authorization" }.type)
    }

    @Test
    fun `changed header is detected`() {
        val before = snapshot(headers = mapOf("Authorization" to listOf("Bearer old")))
        val after  = snapshot(headers = mapOf("Authorization" to listOf("Bearer new")))
        val diff   = RequestDiff.diff(before, after)
        assertTrue(diff.hasChanges)
        assertEquals(DiffType.CHANGED, diff.headerDiffs.first { it.key == "Authorization" }.type)
    }

    @Test
    fun `header comparison is case insensitive`() {
        val before = snapshot(headers = mapOf("authorization" to listOf("Bearer token")))
        val after  = snapshot(headers = mapOf("Authorization" to listOf("Bearer token")))
        val diff   = RequestDiff.diff(before, after)
        assertFalse(diff.hasChanges)
    }

    // ── Body changes ─────────────────────────────────────────────────────

    @Test
    fun `body change is detected`() {
        val before = snapshot(body = """{"name": "old"}""")
        val after  = snapshot(body = """{"name": "new"}""")
        val diff   = RequestDiff.diff(before, after)
        assertTrue(diff.bodyChanged)
        assertTrue(diff.hasChanges)
    }
}

class TraceStoreTest {

    @Test
    fun `startTrace adds entry to inFlight`() {
        TraceStore.clear()
        val entry = TraceEntry(url = "https://api.example.com", method = "GET")
        TraceStore.startTrace(entry)
        assertTrue(TraceStore.entries.isEmpty()) // not completed yet
    }

    @Test
    fun `finishTrace moves entry to completed`() {
        TraceStore.clear()
        val entry = TraceEntry(url = "https://api.example.com", method = "GET")
        TraceStore.startTrace(entry)
        TraceStore.finishTrace(
            traceId    = entry.id,
            response   = null,
            error      = null,
            durationMs = 100L,
            onFinished = {}
        )
        assertEquals(1, TraceStore.entries.size)
    }

    @Test
    fun `maxEntries trims old traces`() {
        TraceStore.clear()
        TraceStore.maxEntries = 3
        repeat(5) {
            val entry = TraceEntry(url = "https://api.example.com", method = "GET")
            TraceStore.startTrace(entry)
            TraceStore.finishTrace(
                traceId    = entry.id,
                response   = null,
                error      = null,
                durationMs = 0L,
                onFinished = {}
            )
        }
        assertEquals(3, TraceStore.entries.size)
        TraceStore.maxEntries = 100 // reset
    }

    @Test
    fun `addSpan attaches span to correct trace`() {
        TraceStore.clear()
        val entry = TraceEntry(url = "https://api.example.com", method = "GET")
        TraceStore.startTrace(entry)

        val snapshot = RequestSnapshot("https://api.example.com", "GET", emptyMap(), null)
        val span = InterceptorSpan(
            interceptorName = "TestInterceptor",
            requestBefore   = snapshot,
            requestAfter    = snapshot,
            response        = null,
            error           = null,
            durationMs      = 10L,
            diff            = RequestDiff.diff(snapshot, snapshot),
        )
        TraceStore.addSpan(entry.id, span)
        TraceStore.finishTrace(
            traceId    = entry.id,
            response   = null,
            error      = null,
            durationMs = 100L,
            onFinished = {}
        )
        assertEquals(1, TraceStore.entries.first().spans.size)
    }

    @Test
    fun `finishTrace sets FAILURE status on error`() {
        TraceStore.clear()
        val entry = TraceEntry(url = "https://api.example.com", method = "GET")
        TraceStore.startTrace(entry)
        TraceStore.finishTrace(
            traceId    = entry.id,
            response   = null,
            error      = Exception("timeout"),
            durationMs = 100L,
            onFinished = {}
        )
        assertEquals(TraceStatus.FAILURE, TraceStore.entries.first().status)
    }
}