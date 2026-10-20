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
                throw SandboxViolation(relative, "a sandbox path must not be blank")
            }

            // Control characters, including NUL.
            if (relative.any { it.code < 0x20 || it.code == 0x7F }) {
                throw SandboxViolation(relative, "a sandbox path must not contain control characters")
            }

            // Absolute paths.
            if (relative.startsWith("/")) {
                throw SandboxViolation(relative, "a sandbox path must be relative")
            }
            if (relative.startsWith("~")) {
                throw SandboxViolation(relative, "a sandbox path must not start with '~'")
            }

            // Windows drive prefixes and UNC paths, as defence in depth.
            if (WINDOWS_DRIVE.matches(relative)) {
                throw SandboxViolation(relative, "a sandbox path must not name a drive")
            }
            if (relative.startsWith("\\\\")) {
                throw SandboxViolation(relative, "a sandbox path must not be a UNC path")
            }

            // Parent traversal, as a segment rather than as a substring.
            val segments = relative.split('/')
            if (segments.any { it == ".." }) {
                throw SandboxViolation(relative, "a sandbox path must not traverse upwards")
            }
            if (segments.any { it == "." }) {
                throw SandboxViolation(relative, "a sandbox path must not contain a '.' segment")
            }

            // Foreign filesystems. These are absolute already, but a
            // relative-looking path that names one is refused too.
            val first = segments.firstOrNull().orEmpty()
            if (first in FOREIGN_FILESYSTEMS) {
                throw SandboxViolation(relative, "a sandbox path must not name the '$first' filesystem")
            }
        }

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
public class SandboxViolation(
    public val requested: String,
    message: String,
) : Exception("$message (requested: '$requested')")
