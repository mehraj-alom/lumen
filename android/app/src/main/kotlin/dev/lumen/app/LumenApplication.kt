package dev.lumen.app

import android.app.Application
import dev.lumen.agent.AgentRuntimeFactory

/**
 * The console's application entry point (process `:ui`).
 *
 * The console holds no run state. It reads the durable state written by the
 * agent process and renders it, and it sends control intents back over the
 * `IAgentRuntime` AIDL interface (`ARCH.md` §5, `API_SPEC.md` §2).
 *
 * The runtime is built here, once, because this is the composition root:
 * everything else takes its dependencies as parameters, and this is where
 * the real ones arrive.
 */
class LumenApplication : Application() {

    /** The runtime, or null before it has been built. */
    var runtime: dev.lumen.agent.service.AgentRuntimeFacade? = null
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = AgentRuntimeFactory(this).start()
    }
}
