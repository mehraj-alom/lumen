package dev.lumen.app

import android.app.Application

/**
 * The application entry point, created once in every process of this app.
 *
 * There are three: the default one the console's activity runs in, `:ui`, which
 * holds the accessibility service and the overlay windows it draws, and
 * `:agent`, which holds the runtime. The manifest is what says so; the `:ui`
 * process exists because an accessibility service cannot be handed a binder by
 * a service of another process.
 *
 * The console holds no run state. It reads the durable state written by the
 * agent process and renders it, and it sends control intents back over the
 * `IAgentRuntime` AIDL interface (`ARCH.md` §5, `API_SPEC.md` §2).
 *
 * Nothing is built here. The runtime belongs to the `:agent` process and is
 * constructed when that process's service is created; building one here as
 * well would have put a second live runtime in the console process, with its
 * own store, its own ledger and its own claim on the quotas, and the console
 * would have been acting on the device from a process that is supposed to be
 * only a view.
 */
class LumenApplication : Application()
