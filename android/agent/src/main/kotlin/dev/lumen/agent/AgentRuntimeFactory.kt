package dev.lumen.agent

import android.content.Context
import dev.lumen.agent.service.AgentRuntimeFacade
import dev.lumen.core.action.ActionLedger
import dev.lumen.core.brain.BrainPort
import dev.lumen.core.brain.BrainRouter
import dev.lumen.core.brain.CloudBrain
import dev.lumen.core.brain.HttpResponse
import dev.lumen.core.brain.HttpTransport
import dev.lumen.core.brain.LocalModelGate
import dev.lumen.core.model.LumenClock
import dev.lumen.core.model.SystemLumenClock
import dev.lumen.core.policy.ApprovalBroker
import dev.lumen.core.policy.PolicyEngine
import dev.lumen.core.protocol.ContentEgressGuard
import dev.lumen.core.quota.MemoryProbe
import dev.lumen.core.quota.QuotaWatchdog
import dev.lumen.core.quota.RamBudget
import dev.lumen.core.sandbox.SandboxRoot
import dev.lumen.core.sandbox.StorageQuota
import dev.lumen.core.sandbox.WorkspaceManager
import dev.lumen.core.store.DatabaseKeyProvider
import dev.lumen.core.store.FileLedgerSink
import dev.lumen.core.store.LumenDatabase
import dev.lumen.core.store.RoomStateStore
import dev.lumen.platform.accessibility.AccessibilityBridgeClient
import dev.lumen.platform.accessibility.UiEventTracker
import dev.lumen.platform.idle.IdleGate
import dev.lumen.platform.idle.PlatformScreenState
import dev.lumen.platform.idle.TouchObserver
import dev.lumen.platform.virtualdisplay.VirtualDisplayManager
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The composition root.
 *
 * This is the one place where the pieces are put together. Everything else
 * takes its dependencies as parameters, which is why the loop is testable
 * without a device; this is where the real ones arrive.
 */
public class AgentRuntimeFactory(private val context: Context, private val clock: LumenClock = SystemLumenClock) {

    /** The sandbox root: the app-scoped directory everything lives in. */
    public fun sandboxRoot(): SandboxRoot = SandboxRoot(
        java.io.File(context.filesDir, SANDBOX_DIRECTORY),
    )

    /** The storage accounting for the sandbox. */
    public fun storageQuota(): StorageQuota = StorageQuota()

    /** The only way to read or write a file. */
    public fun workspace(root: SandboxRoot, quota: StorageQuota): WorkspaceManager = WorkspaceManager(root, quota, clock)

    /** The encrypted state store. */
    public fun store(): RoomStateStore {
        val database = LumenDatabase.open(context, DatabaseKeyProvider(context))
        return RoomStateStore(database)
    }

    /** The approval broker, over the same store. */
    public fun approvals(store: RoomStateStore): ApprovalBroker = ApprovalBroker(store, clock)

    /** The policy engine. */
    public fun policy(): PolicyEngine = PolicyEngine()

    /**
     * The write-ahead ledger.
     *
     * Every action is recorded in the `traces/` bucket, and the write is
     * forced to the device before the call returns, so an action that cannot
     * be audited is never dispatched (invariant I6). The sink is the ledger's
     * only path out of the process, and it goes through the sandbox.
     */
    public fun ledger(workspace: WorkspaceManager): ActionLedger = ActionLedger(FileLedgerSink(workspace))

    /** The quota engine, with the platform's memory probe. */
    public fun quota(): QuotaWatchdog = QuotaWatchdog(
        budget = RamBudget(),
        probe = MemoryProbe { platformMemoryMiB() },
    )

    /**
     * The brain.
     *
     * The local model is used when one is installed and fits the remaining
     * budget, and the cloud otherwise. The user can pin either.
     */
    public fun brain(egress: ContentEgressGuard): BrainPort {
        val cloud = CloudBrain(
            transport = OkHttpTransport(),
            baseUrl = cloudBaseUrl(),
            apiKey = cloudApiKey(),
            modelId = cloudModelId(),
            egress = egress,
        )

        return BrainRouter(
            local = null,
            cloud = cloud,
            localGate = LocalModelGate { LOCAL_MODEL_INSTALLED },
        )
    }

    /**
     * The environment: the device the agent acts on.
     *
     * The bridge is the only path to the device. The idle gate and the
     * virtual display decide which surface an action is allowed to use.
     */
    public fun environment(bridge: AccessibilityBridgeClient): AgentEnvironment {
        val tracker = UiEventTracker()
        val touchObserver = TouchObserver(clock)
        val gate = IdleGate(
            screenState = PlatformScreenState(context),
            touchObserver = touchObserver,
        )
        return AgentEnvironment(
            bridge = bridge,
            idleGate = gate,
            virtualDisplay = VirtualDisplayManager(context),
            eventTracker = tracker,
            touchObserver = touchObserver,
        )
    }

    /**
     * Builds everything and starts the runtime.
     *
     * This is what the application calls, once, when it starts.
     */
    public fun start(): AgentRuntimeFacade {
        val root = sandboxRoot()
        val quota = storageQuota()
        val workspace = workspace(root, quota)
        val store = store()
        val egress = ContentEgressGuard(spectatingEnabled = false)
        val bridge = AccessibilityBridgeClient(context)
        bridge.connect()

        return AgentRuntimeFacade(
            environment = environment(bridge),
            brain = brain(egress),
            store = store,
            ledger = ledger(workspace),
            approvals = approvals(store),
            policy = policy(),
            quota = quota(),
        )
    }

    private fun platformMemoryMiB(): Int {
        val info = android.app.ActivityManager.MemoryInfo()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        manager.getMemoryInfo(info)
        // The division happens in bytes: the used figure passes 2 GiB on a
        // modern phone, and narrowing it to Int first would wrap.
        return ((info.totalMem - info.availMem) / BYTES_PER_MIB).toInt()
    }

    private fun cloudBaseUrl(): String = settings().getString("cloud_brain_url", DEFAULT_CLOUD_URL) ?: DEFAULT_CLOUD_URL

    private fun cloudApiKey(): String = settings().getString("cloud_brain_key", "") ?: ""

    private fun cloudModelId(): String = settings().getString("cloud_brain_model", DEFAULT_CLOUD_MODEL) ?: DEFAULT_CLOUD_MODEL

    private fun settings(): android.content.SharedPreferences = context.getSharedPreferences("lumen_settings", Context.MODE_PRIVATE)

    public companion object {
        /** The directory the sandbox lives in, inside the app's own storage. */
        public const val SANDBOX_DIRECTORY: String = "sandbox"

        /** The default cloud brain endpoint. */
        public const val DEFAULT_CLOUD_URL: String = "https://api.lumen.app/v1"

        /** The default cloud model. The model id is pinned, so a provider-side
         *  swap cannot silently change the agent's behaviour. */
        public const val DEFAULT_CLOUD_MODEL: String = "lumen-planner-v1"

        /** The memory probe reads bytes; every quota in `AGENTS.md` §2 is in MiB. */
        private const val BYTES_PER_MIB: Int = 1024 * 1024

        /**
         * Whether a quantised local model is installed under `models/` and fits
         * the remaining RAM budget.
         *
         * No local model ships with this build, so `D2` always routes to the
         * cloud and a `LOCAL` pin has nothing to bind to. Admitting an
         * installed model is the change that flips this flag, and it has to go
         * through `QuotaWatchdog.reserve` first (I1) — the reserve check is the
         * reason this is a decision rather than a lookup.
         */
        private const val LOCAL_MODEL_INSTALLED: Boolean = false
    }
}

/** The time allowed to reach the cloud endpoint before the call is abandoned. */
private const val CONNECT_TIMEOUT_SECONDS: Long = 15L

/**
 * The time allowed for one completion.
 *
 * A planner turn streams a long reasoning trace, so a read timeout tuned for
 * a chat reply would cut the call short mid-plan. It is also the ceiling on
 * how long a single step can hold the run.
 */
private const val READ_TIMEOUT_SECONDS: Long = 120L

/** The OkHttp transport the cloud brain uses. */
public class OkHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build(),
) : HttpTransport {

    override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (key, value) -> header(key, value) } }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            return HttpResponse(
                code = response.code,
                body = response.body?.string().orEmpty(),
            )
        }
    }
}
