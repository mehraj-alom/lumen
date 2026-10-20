package dev.lumen.app

import android.app.Application

/**
 * Console-side application entry point (process `:ui`).
 *
 * The console owns no run state. It reads the durable state written by the
 * agent process and renders it, and it sends control intents back over the
 * `IAgentRuntime` AIDL interface (ARCH.md §5, `API_SPEC.md` §2).
 */
class LumenApplication : Application()
