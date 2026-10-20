package dev.lumen.core.sandbox

import java.io.File
import java.io.IOException

/**
 * The root of the app-scoped sandbox.
 *
 * The root is canonicalised once, at construction. Every path is resolved
 * against it and canonicalised again, and the result must still be inside
 * the root. That single check covers both the traversal cases the path type
 * already rejects and the ones it cannot: a symlink inside the sandbox that
 * points outside it.
 */
public class SandboxRoot(rootDirectory: File) {

    /** The canonical root directory. */
    public val root: File = try {
        rootDirectory.canonicalFile
    } catch (error: IOException) {
        throw SandboxViolation(rootDirectory.path, "the sandbox root could not be resolved")
    }

    init {
        if (!root.isDirectory && !root.mkdirs()) {
            throw SandboxViolation(root.path, "the sandbox root could not be created")
        }
    }

    /**
     * Resolves [path] to a file inside the root.
     *
     * @throws SandboxViolation when the resolved file would be outside the
     *   root, which is what a symlink escape looks like.
     */
    public fun resolve(path: SandboxPath): File {
        val candidate = File(root, path.toRelativePath())
        val canonical = try {
            candidate.canonicalFile
        } catch (error: IOException) {
            throw SandboxViolation(path.relative, "the path could not be resolved")
        }

        if (!isInsideRoot(canonical)) {
            throw SandboxViolation(path.relative, "the path resolves outside the sandbox root")
        }
        return canonical
    }

    /** Whether [file] is inside the root. */
    public fun isInsideRoot(file: File): Boolean {
        val rootPath = root.path
        val filePath = file.path
        return filePath == rootPath || filePath.startsWith(rootPath + File.separator)
    }

    /** The bucket [path] belongs to, or null. */
    public fun bucketOf(path: SandboxPath): StorageBucket? = StorageBucket.bucketOf(path)

    /** The directory of [bucket], created if it is missing. */
    public fun bucketDirectory(bucket: StorageBucket): File {
        val directory = File(root, bucket.name.lowercase())
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw SandboxViolation(bucket.name, "the bucket directory could not be created")
        }
        return directory
    }
}
