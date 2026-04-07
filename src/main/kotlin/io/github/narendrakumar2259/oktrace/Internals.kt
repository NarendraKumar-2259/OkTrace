package io.github.narendrakumar2259.oktrace

import okio.Buffer

private const val PREVIEW_BYTES = 4096L

internal fun okhttp3.Response.captureBodyPreview(): String? = try {
    val bytes = peekBody(PREVIEW_BYTES).bytes()
    if (bytes.isLikelyText()) bytes.toString(Charsets.UTF_8) else null
} catch (_: Exception) {
    null
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

internal fun okhttp3.Request.captureBodyPreview(): String? = try {
    val copy = newBuilder().build()
    val buffer = Buffer()
    copy.body?.writeTo(buffer)
    val bytes = buffer.readByteArray()
    if (bytes.isLikelyText()) bytes.toString(Charsets.UTF_8).take(4096) else null
} catch (_: Exception) { null }

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