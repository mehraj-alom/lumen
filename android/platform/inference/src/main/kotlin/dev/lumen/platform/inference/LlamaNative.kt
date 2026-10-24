package dev.lumen.platform.inference

import dev.lumen.core.brain.NativeInference

/**
 * The native inference runtime, loaded from the `localInference` flavour.
 *
 * The class is present in every build; the native library is not. On a build
 * without it, every method reports that the runtime is not ready, and the
 * router escalates to the cloud.
 */
public object LlamaNative : NativeInference {

    /**
     * Why the native library is missing, or null when it loaded.
     *
     * The reason is kept because the router reports it when a `LOCAL` pin
     * cannot be honoured: "there is no local model" and "the runtime that
     * would run it is not in this build" are different answers to the user.
     */
    private val loadFailure: UnsatisfiedLinkError? = try {
        System.loadLibrary("lumen-inference")
        null
    } catch (error: UnsatisfiedLinkError) {
        error
    }

    private val loaded: Boolean = loadFailure == null

    /** The message of the load failure, for a route decision that has to explain itself. */
    public val unavailableReason: String? get() = loadFailure?.message

    override fun isReady(): Boolean = loaded && nativeIsReady()

    override fun loadedModel(): String? = if (loaded) nativeLoadedModel() else null

    override fun loadModel(path: String, contextSize: Int): Boolean {
        if (!loaded) return false
        return nativeLoadModel(path, contextSize)
    }

    override fun unloadModel() {
        if (loaded) nativeUnloadModel()
    }

    override fun complete(prompt: String, maxTokens: Int, seed: Long): String? {
        if (!loaded) return null
        return nativeComplete(prompt, maxTokens, seed)
    }

    private external fun nativeIsReady(): Boolean
    private external fun nativeLoadedModel(): String?
    private external fun nativeLoadModel(path: String, contextSize: Int): Boolean
    private external fun nativeUnloadModel()
    private external fun nativeComplete(prompt: String, maxTokens: Int, seed: Long): String?
}
