package io.github.narendrakumar2259.oktrace

import okio.Buffer
import okio.ForwardingSink
import java.io.IOException
import okio.buffer

private const val PREVIEW_BYTES = 4096L

/** Placeholder recorded instead of a body OkTrace must not read. */
internal const val UNREADABLE_BODY = "<one-shot or duplex body, not captured>"

internal fun okhttp3.Response.captureBodyPreview(): String? = try {
    if (isStreaming()) {
        null
    } else {
        val bytes = peekBody(PREVIEW_BYTES).bytes()
        if (bytes.isLikelyText()) bytes.toString(Charsets.UTF_8) else null
    }
} catch (_: Exception) {
    null
}

/**
 * `peekBody` blocks until it has [PREVIEW_BYTES] or the stream ends. For a
 * protocol upgrade or a server-sent event stream that can take forever, so
 * these responses are never previewed.
 */
private fun okhttp3.Response.isStreaming(): Boolean {
    if (code == 101) return true
    val type = body?.contentType() ?: return false
    return type.type == "text" && type.subtype == "event-stream"
}

internal fun okhttp3.Headers.toMap(): Map<String, List<String>> {
    val result = LinkedHashMap<String, MutableList<String>>()
    for (i in 0 until size) result.getOrPut(name(i)) { mutableListOf() }.add(value(i))
    return result
}

internal fun ByteArray.isLikelyText(): Boolean {
    if (isEmpty()) return true
    val sample = take(512)
    val printable = sample.count { b ->
        val c = b.toInt() and 0xFF
        c in 0x09..0x0D || c in 0x20..0x7E || c >= 0x80
    }
    return printable.toDouble() / sample.size > 0.85
}

/**
 * Captures at most [PREVIEW_BYTES] of the request body.
 *
 * One-shot and duplex bodies are never written here: writing them would use
 * up the only copy, and the real request would then fail. Other bodies are
 * written into a sink that stops after the preview size, so a large upload is
 * not copied into memory once per observed interceptor.
 */
internal fun okhttp3.Request.captureBodyPreview(): String? {
    val body = body ?: return null
    if (body.isOneShot() || body.isDuplex()) return UNREADABLE_BODY
    val buffer = Buffer()
    try {
        val capped = CappedSink(buffer, PREVIEW_BYTES).buffer()
        body.writeTo(capped)
        capped.flush()
    } catch (_: PreviewFull) {
        // Enough bytes for the preview; the rest of the body is not needed.
    } catch (_: Exception) {
        return null
    }
    val bytes = buffer.readByteArray()
    return if (bytes.isLikelyText()) bytes.toString(Charsets.UTF_8) else null
}

private class PreviewFull : IOException()

private class CappedSink(private val target: Buffer, private val limit: Long) : ForwardingSink(target) {
    override fun write(source: Buffer, byteCount: Long) {
        val room = limit - target.size
        if (room <= 0) throw PreviewFull()
        val toWrite = minOf(room, byteCount)
        super.write(source, toWrite)
        if (toWrite < byteCount) {
            source.skip(byteCount - toWrite)
            throw PreviewFull()
        }
    }
}

internal object RequestDiff {
    fun diff(before: RequestSnapshot, after: RequestSnapshot) = RequestDiffResult(
        urlChanged = before.url != after.url,
        methodChanged = before.method != after.method,
        headerDiffs = diffHeaders(before.headers, after.headers),
        bodyChanged = before.bodyPreview != after.bodyPreview,
    )

    private fun diffHeaders(
        before: Map<String, List<String>>,
        after: Map<String, List<String>>,
    ): List<HeaderDiff> {
        val keys = sortedSetOf(String.CASE_INSENSITIVE_ORDER)
            .also { it.addAll(before.keys); it.addAll(after.keys) }
        return keys.map { key ->
            val b = before.getCI(key)?.joinToString("; ")
            val a = after.getCI(key)?.joinToString("; ")
            HeaderDiff(
                key, b, a, when {
                    b == null && a != null -> DiffType.ADDED
                    b != null && a == null -> DiffType.REMOVED
                    b != a -> DiffType.CHANGED
                    else -> DiffType.UNCHANGED
                }
            )
        }.sortedWith(compareBy({ it.type == DiffType.UNCHANGED }, { it.key }))
    }

    private fun Map<String, List<String>>.getCI(key: String) =
        entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
}
