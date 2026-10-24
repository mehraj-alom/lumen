package dev.lumen.agent.crash

import android.util.Log
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
public class CrashReporter(private val workspace: WorkspaceManager, private val version: String = "0.1.0") {

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
            // Only the exception's class is logged: the message of a failed
            // write is exactly the kind of thing that carries a sandbox path,
            // and the redaction rules above exist to keep that out of a report.
            Log.w(TAG, "the crash report could not be written: ${error.javaClass.simpleName}")
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
        val fileName = ABSOLUTE_PATH.replace(value) { match -> match.value.substringAfterLast('/') }
        val unpaired = SECRET_PAIR.replace(fileName, "[redacted]")
        val result = BARE_MARKER.replace(unpaired, "[redacted]")
        return if (result.length > MAX_LENGTH) result.take(MAX_LENGTH) + "…" else result
    }

    public companion object {
        private const val TAG = "LumenCrashReporter"

        /** The longest message or stack trace that is kept. */
        public const val MAX_LENGTH: Int = 4_000

        /** The words that mark a value as sensitive. */
        public val SENSITIVE_MARKERS: List<String> = listOf(
            "password", "passwd", "secret", "token", "authorization", "bearer",
            "card number", "cvv", "otp", "one-time",
        )

        /** An absolute path; only its last component is kept. */
        private val ABSOLUTE_PATH = Regex("""(?:/[A-Za-z0-9_.\-]+)+""")

        /** A sensitive word with the value next to it: both go. */
        private val SECRET_PAIR = Regex(
            "(?i)\\b(" + SENSITIVE_MARKERS.joinToString("|") { Regex.escape(it) } + ")[\\s:=]+\\S+",
        )

        /** A sensitive word on its own. A file named `secret.json` is not a secret. */
        private val BARE_MARKER = Regex(
            "(?i)\\b(" + SENSITIVE_MARKERS.joinToString("|") { Regex.escape(it) } + ")(?![.\\w])",
        )
    }
}
