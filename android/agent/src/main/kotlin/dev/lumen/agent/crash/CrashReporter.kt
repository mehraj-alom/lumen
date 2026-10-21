package dev.lumen.agent.crash

import dev.lumen.core.sandbox.SandboxPath
import dev.lumen.core.sandbox.WorkspaceManager
import java.io.PrintWriter
import java.io.StringWriter

/**
 * The crash reporter.
 *
 * **Opt-in, and off by default.** Nothing is written unless the user has
 * turned it on, and nothing leaves the device even then: the report is
 * written into the sandbox, where the user can read it, attach it, or
 * delete it. Sending it anywhere is a separate, explicit act.
 *
 * The redaction is structural rather than a filter. A report is built from
 * a fixed set of fields — the version, the thread, the exception class, the
 * message, and the stack trace — and the stack trace is scrubbed of the
 * things that carry screen content or file paths.
 */
public class CrashReporter(
    private val workspace: WorkspaceManager,
    private val version: String = "0.1.0",
) {

    /** Whether reports are written at all. */
    @Volatile
    public var enabled: Boolean = false

    /** The reports that were written. */
    public val written: MutableList<CrashReport> = mutableListOf()

    /**
     * Records a crash.
     *
     * @return the report, or null when reporting is off
     */
    public fun record(throwable: Throwable, threadName: String = Thread.currentThread().name): CrashReport? {
        if (!enabled) return null

        val report = CrashReport(
            version = version,
            threadName = threadName,
            exceptionClass = throwable.javaClass.name,
            message = redact(throwable.message ?: ""),
            stackTrace = redact(stackTraceOf(throwable)),
            createdAt = java.time.Instant.now().toString(),
        )

        written += report

        try {
            val path = SandboxPath.of("traces/crash-${System.currentTimeMillis()}.json")
            workspace.write(path, report.render().toByteArray())
        } catch (error: Exception) {
            // A crash reporter that crashes is worse than no crash reporter.
        }

        return report
    }

    private fun stackTraceOf(throwable: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { throwable.printStackTrace(it) }
        return writer.toString()
    }

    /**
     * Removes the things a stack trace must not carry.
     *
     * A stack trace from an automation agent is full of absolute paths, and
     * an exception message can contain the text that was on the screen. The
     * paths are reduced to their file names, and the message is truncated.
     */
    private fun redact(value: String): String {
        var result = value
        for (marker in SENSITIVE_MARKERS) {
            result = result.replace(marker, "[redacted]")
        }
        result = ABSOLUTE_PATH.replace(result) { match -> match.groupValues[1] + "/" + match.groupValues[2] }
        return if (result.length > MAX_LENGTH) result.take(MAX_LENGTH) + "…" else result
    }

    public companion object {
        /** The longest message or stack trace that is kept. */
        public const val MAX_LENGTH: Int = 4_000

        /** The words that are replaced wherever they appear. */
        public val SENSITIVE_MARKERS: List<String> = listOf(
            "password", "passwd", "secret", "token", "authorization", "bearer",
            "card number", "cvv", "otp", "one-time",
        )

        /** An absolute path, reduced to its directory and its file name. */
        private val ABSOLUTE_PATH = Regex("""(/[A-Za-z0-9_.\-]+)+/([A-Za-z0-9_.\-]+)""")
    }
}
