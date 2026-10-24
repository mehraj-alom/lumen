package dev.lumen.core.brain

import dev.lumen.core.model.BrainPin

/**
 * The brain one run is told to use.
 *
 * The endpoint, the credential and the model are the user's own configuration,
 * [pin] is where they want the reasoning to run (`D2`), and [screenTextEgress]
 * is their consent under invariant `I11`: text read from the screen reaches a
 * cloud model only when they have said so.
 *
 * A run reads this once, when it starts, so changing the configuration while a
 * run is going does not change that run.
 */
public data class BrainConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelId: String = "",
    val pin: BrainPin = BrainPin.AUTO,
    val screenTextEgress: Boolean = false,
) {
    /** Whether there is enough here to ask a model for anything. */
    public val isUsable: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && modelId.isNotBlank()
}

/**
 * The brain for the configuration a run arrived with.
 *
 * Building the brain per run, rather than once per process, is what lets a run
 * carry its own consent: the guard inside a cloud brain is constructed from the
 * configuration that run was started with.
 */
public fun interface BrainFactory {
    /** The brain for [config]. */
    public fun create(config: BrainConfig): BrainPort
}
