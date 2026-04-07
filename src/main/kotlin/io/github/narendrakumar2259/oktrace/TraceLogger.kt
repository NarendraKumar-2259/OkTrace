package io.github.narendrakumar2259.oktrace

internal object TraceLogger {

    private const val LINE = "──────────────────────────────────────────────────────"

    fun log(tag: String, entry: TraceEntry) {
        if (!OkTrace.enabled) return

        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("┌$LINE")
        sb.appendLine("│ ▶  ${entry.method}  ${entry.url}")
        sb.appendLine("├$LINE")

        if (entry.spans.isEmpty()) {
            sb.appendLine("│  No observed interceptors.")
            sb.appendLine("│  Wrap yours: .addInterceptor(OkTrace.observe(YourInterceptor()))")
        } else {
            entry.spans.forEach { span ->
                val label = if (span.diff.hasChanges) "[MODIFIED]" else "[pass-through]"
                val name  = span.interceptorName.padEnd(28)
                sb.appendLine("│  $name ${span.durationMs}ms   $label")

                span.diff.changedHeaders.forEach { diff ->
                    val prefix = when (diff.type) {
                        DiffType.ADDED     -> "+"
                        DiffType.REMOVED   -> "-"
                        DiffType.CHANGED   -> "~"
                        DiffType.UNCHANGED -> " "
                    }
                    sb.appendLine("│    $prefix ${diff.key}: ${diff.after ?: diff.before ?: ""}")
                    if (diff.type == DiffType.CHANGED && diff.before != null)
                        sb.appendLine("│      was: ${diff.before}")
                }

                if (span.diff.urlChanged)  sb.appendLine("│    ~ URL changed")
                if (span.diff.bodyChanged) sb.appendLine("│    ~ Body changed")
                span.error?.let { sb.appendLine("│    ✖ ${it.javaClass.simpleName}: ${it.message}") }
            }
        }

        sb.appendLine("├$LINE")

        when {
            entry.error != null ->
                sb.appendLine("│ ✖  FAILED  ${entry.error.javaClass.simpleName}: ${entry.error.message}")
            entry.finalResponse != null -> {
                val r = entry.finalResponse
                sb.appendLine("│ ◀  ${r.code} ${r.message}  •  Total: ${entry.totalDurationMs}ms")
                r.contentType?.let { sb.appendLine("│    Content-Type: $it") }
                r.bodyPreview?.let { body ->
                    val preview = body.take(300).replace("\n", "\n│    ")
                    sb.appendLine("│    Body: $preview")
                }
            }
        }

        sb.append("└$LINE")

        println(sb.toString())
    }
}