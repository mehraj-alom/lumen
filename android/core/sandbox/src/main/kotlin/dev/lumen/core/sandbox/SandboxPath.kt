package dev.lumen.core.sandbox

/**
 * A path inside the sandbox, relative to the sandbox root.
 *
 * The type refuses to be constructed from anything that could escape the
 * root, so an invalid path fails at the point of construction rather than at
 * the point of the write. Every path in the system is one of these; there is
 * no second way to name a file (`CODING_STANDARDS.md` §6).
 *
 * The checks that need a filesystem — canonicalisation and symlink escape —
 * belong to [SandboxRoot], which knows where the root is. The checks here are
 * the ones that can be decided from the string alone.
 */
@JvmInline
public value class SandboxPath(public val relative: String) {

    init {
        Companion.validate(relative)
    }

    /** The path segments, with the empty ones removed. */
    public val segments: List<String>
        get() = relative.split('/').filter { it.isNotEmpty() }

    /** The first segment, which names the storage bucket. */
    public val bucketName: String
        get() = segments.firstOrNull().orEmpty()

    /** The path as a clean relative path. */
    public fun toRelativePath(): String = segments.joinToString("/")

    override fun toString(): String = relative

    public companion object {

        /**
         * Builds a path from a relative string.
         *
         * @throws SandboxViolation when the path could escape the root.
         */
        public fun of(relative: String): SandboxPath = SandboxPath(relative)

        /**
         * The syntactic validation, exposed for callers that already hold a
         * [SandboxPath] and want to re-check it.
         */
        public fun validate(relative: String) {
            if (relative.isBlank()) {
                refuse(relative, "a sandbox path must not be blank")
            }

            // Control characters, including NUL.
            if (relative.any { it.code < LOWEST_PRINTABLE || it.code == DELETE }) {
                refuse(relative, "a sandbox path must not contain control characters")
            }

            // Absolute paths.
            if (relative.startsWith("/")) {
                refuse(relative, "a sandbox path must be relative")
            }
            if (relative.startsWith("~")) {
                refuse(relative, "a sandbox path must not start with '~'")
            }

            // Windows drive prefixes and UNC paths, as defence in depth.
            if (WINDOWS_DRIVE.containsMatchIn(relative)) {
                refuse(relative, "a sandbox path must not name a drive")
            }
            if (relative.startsWith("\\\\")) {
                refuse(relative, "a sandbox path must not be a UNC path")
            }

            // Parent traversal, as a segment rather than as a substring.
            val segments = relative.split('/')
            if (segments.any { it == ".." }) {
                refuse(relative, "a sandbox path must not traverse upwards")
            }
            if (segments.any { it == "." }) {
                refuse(relative, "a sandbox path must not contain a '.' segment")
            }

            // Foreign filesystems. These are absolute already, but a
            // relative-looking path that names one is refused too.
            val first = segments.firstOrNull().orEmpty()
            if (first in FOREIGN_FILESYSTEMS) {
                refuse(relative, "a sandbox path must not name the '$first' filesystem")
            }
        }

        /**
         * The single throw site of [validate], so that adding a check does not
         * add a way out.
         */
        private fun refuse(relative: String, reason: String): Nothing = throw SandboxViolation(relative, reason)

        private const val LOWEST_PRINTABLE = 0x20
        private const val DELETE = 0x7F

        private val WINDOWS_DRIVE = Regex("^[A-Za-z]:")
        private val FOREIGN_FILESYSTEMS = setOf(
            "proc",
            "sys",
            "dev",
            "sdcard",
            "storage",
            "mnt",
            "etc",
        )
    }
}

/** A path that cannot be used inside the sandbox. */
public class SandboxViolation(public val requested: String, message: String, cause: Throwable? = null) :
    Exception("$message (requested: '$requested')", cause)
