package dev.lumen.platform.inference

/**
 * The native inference runtime, loaded from the `localInference` flavour.
 *
 * The class is present in every build; the native library is not. On a build
 * without it, every method reports that the runtime is not ready, and the
 * router escalates to the cloud.
 */
public object LlamaNative : NativeInference {

    private val loaded: Boolean = try {
        System.loadLibrary("lumen-inference")
        true
    } catch (error: UnsatisfiedLinkError) {
        false
    }

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
