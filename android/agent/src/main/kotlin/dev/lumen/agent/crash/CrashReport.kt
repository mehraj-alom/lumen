package dev.lumen.agent.crash

/**
 * A redacted crash report.
 *
 * The redaction is not a best effort. The report is built from a whitelist
 * of fields, and the screen content — which is the thing an agent's crash
 * is most likely to contain — never reaches it in the first place.
 */
public data class CrashReport(
    val version: String,
    val threadName: String,
    val exceptionClass: String,
    val message: String,
    val stackTrace: String,
    val createdAt: String,
) {
    /** The report as the JSON-shaped text that is written to the sandbox. */
    public fun render(): String = buildString {
        appendLine("{")
        appendLine("  \"version\": \"${escape(version)}\",")
        appendLine("  \"thread\": \"${escape(threadName)}\",")
        appendLine("  \"exception\": \"${escape(exceptionClass)}\",")
        appendLine("  \"message\": \"${escape(message)}\",")
        appendLine("  \"createdAt\": \"${escape(createdAt)}\",")
        appendLine("  \"stackTrace\": \"${escape(stackTrace)}\"")
        appendLine("}")
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")
}
