package dev.lumen.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import dev.lumen.agent.crash.CrashReporter
import dev.lumen.agent.service.AgentRuntimeFacade
import dev.lumen.agent.service.FramePipeline
import dev.lumen.core.action.ActionLedger
import dev.lumen.core.brain.BrainConfig
import dev.lumen.core.brain.BrainFactory
import dev.lumen.core.brain.BrainPort
import dev.lumen.core.brain.BrainRouter
import dev.lumen.core.brain.CloudBrain
import dev.lumen.core.brain.HttpResponse
import dev.lumen.core.brain.HttpTransport
import dev.lumen.core.brain.LocalModelGate
import dev.lumen.core.brain.PriceTable
import dev.lumen.core.model.LumenClock
import dev.lumen.core.model.SystemLumenClock
import dev.lumen.core.policy.ApprovalBroker
import dev.lumen.core.policy.PolicyEngine
import dev.lumen.core.protocol.ContentEgressGuard
import dev.lumen.core.quota.BatteryGovernor
import dev.lumen.core.quota.BatteryProbe
import dev.lumen.core.quota.BatteryReading
import dev.lumen.core.quota.MemoryProbe
import dev.lumen.core.quota.QuotaWatchdog
import dev.lumen.core.quota.RamBudget
import dev.lumen.core.quota.ThermalGovernor
import dev.lumen.core.quota.ThermalProbe
import dev.lumen.core.quota.ThermalReading
import dev.lumen.core.quota.ThermalStatus
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
import dev.lumen.platform.idle.IdleWindow
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

    /**
     * The one HTTP client this process makes calls with.
     *
     * OkHttp keeps a thread pool and a connection pool per client, and a brain
     * is built per run now; a client per run would leave threads behind that
     * outlive it (`AGENTS.md` §2 caps the workers).
     */
    private val transport: HttpTransport by lazy { OkHttpTransport() }

    /** The one storage accounting of this process. */
    private val storage: StorageQuota = StorageQuota()

    /** The one workspace of this process, holding [storage]. */
    private val sandbox: WorkspaceManager by lazy { WorkspaceManager(sandboxRoot(), storage, clock) }

    /** The idle window the gate measures against, and the one the settings screen moves. */
    private val idleWindow: IdleWindow = IdleWindow()

    /** The sandbox root: the app-scoped directory everything lives in. */
    public fun sandboxRoot(): SandboxRoot = SandboxRoot(
        java.io.File(context.filesDir, SANDBOX_DIRECTORY),
    )

    /**
     * The storage accounting for the sandbox.
     *
     * One instance per process, because it is the thing that decides whether a
     * write fits. A second copy would count only its own bytes, and the cap in
     * `AGENTS.md` §2 would become a sum of two under-counters — which is `I10`
     * said as a number rather than a wish.
     */
    public fun storageQuota(): StorageQuota = storage

    /** The only way to read or write a file. */
    public fun workspace(): WorkspaceManager = sandbox

    /**
     * The crash reporter for this process.
     *
     * It writes through the same workspace as the ledger, so a report is
     * accounted against the `traces/` bucket like anything else the runtime
     * puts on the device, and it is inert until the user turns reporting on.
     * The version it stamps is the reporter's own default, which is the
     * `versionName` in `app/build.gradle.kts`; the two move together at a
     * release, in `RELEASE.md`.
     */
    public fun crashReporter(): CrashReporter = CrashReporter(workspace())

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

    /** Reads the device's thermal state from the platform. */
    @Suppress("NewApi")
    public fun thermalProbe(): ThermalProbe = ThermalProbe {
        val manager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val status = manager.currentThermalStatus
        ThermalReading(
            temperatureCelsius = thermalCelsius(status),
            status = thermalStatus(status),
        )
    }

    /** Reads the device's battery state from the platform. */
    public fun batteryProbe(): BatteryProbe = BatteryProbe {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent = context.registerReceiver(null, filter)
        if (intent == null) {
        BatteryReading(0, false)
    } else {
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0
            BatteryReading(levelPercent = percent, isCharging = isCharging)
        }
    }

    /** The thermal governor: slows the agent when the device is hot. */
    public fun thermalGovernor(): ThermalGovernor = ThermalGovernor()

    /** The battery governor: suspends autonomous runs on low battery. */
    public fun batteryGovernor(): BatteryGovernor = BatteryGovernor()

    /** The frame pipeline: captures into the sandbox, publishes by path. */
    public fun framePipeline(): FramePipeline = FramePipeline(workspace(), clock)

    /**
     * The brain for one run.
     *
     * The endpoint, the credential and the model id arrive with the goal, so
     * the run is planned by the model the user configured, and the egress guard
     * inside it carries the consent that run was started with (`I11`). The
     * router is `D2`: local when a qualifying model is installed and fits the
     * budget, otherwise cloud, and a pin always wins.
     */
    public fun brain(config: BrainConfig): BrainPort {
        val cloud = CloudBrain(
            transport = transport,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            modelId = config.modelId,
            egress = ContentEgressGuard(spectatingEnabled = config.screenTextEgress),
        )

        return BrainRouter(
            local = null,
            cloud = cloud,
            pin = config.pin,
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
            idleWindow = idleWindow,
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
     * This is what the agent service calls when it is created, once per process.
     * The brain is not built here: it is built per run, from the configuration
     * that run arrives with, because the consent to send screen text is the
     * user's decision for that run (`I11`).
     */
    public fun start(): AgentRuntimeFacade {
        val storage = storageQuota()
        val workspace = workspace()
        val store = store()
        val bridge = AccessibilityBridgeClient(context)
        bridge.connect()

        return AgentRuntimeFacade(
            environment = environment(bridge),
            brains = BrainFactory { config -> brain(config) },
            store = store,
            ledger = ledger(workspace),
            approvals = approvals(store),
            policy = policy(),
            quota = quota(),
            storage = storage,
            indicator = bridge,
            crashes = crashReporter(),
            idleWindow = idleWindow,
            thermalProbe = thermalProbe(),
            batteryProbe = batteryProbe(),
            thermalGovernor = thermalGovernor(),
            batteryGovernor = batteryGovernor(),
            priceTable = PriceTable.Empty,
            framePipeline = framePipeline(),
        )
    }

    private fun thermalCelsius(status: Int): Double = when (status) {
        PowerManager.THERMAL_STATUS_LIGHT -> THERMAL_LIGHT_CELSIUS
        PowerManager.THERMAL_STATUS_MODERATE -> THERMAL_MODERATE_CELSIUS
        PowerManager.THERMAL_STATUS_SEVERE -> THERMAL_SEVERE_CELSIUS
        PowerManager.THERMAL_STATUS_CRITICAL -> THERMAL_CRITICAL_CELSIUS
        PowerManager.THERMAL_STATUS_EMERGENCY -> THERMAL_EMERGENCY_CELSIUS
        PowerManager.THERMAL_STATUS_SHUTDOWN -> THERMAL_SHUTDOWN_CELSIUS
        else -> THERMAL_NORMAL_CELSIUS
    }

    private fun thermalStatus(status: Int): ThermalStatus = when (status) {
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
        PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SHUTDOWN
        else -> ThermalStatus.NONE
    }

    private fun platformMemoryMiB(): Int {
        val info = android.app.ActivityManager.MemoryInfo()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        manager.getMemoryInfo(info)
        // The division happens in bytes: the used figure passes 2 GiB on a
        // modern phone, and narrowing it to Int first would wrap.
        return ((info.totalMem - info.availMem) / BYTES_PER_MIB).toInt()
    }

    public companion object {
        /** The directory the sandbox lives in, inside the app's own storage. */
        public const val SANDBOX_DIRECTORY: String = "sandbox"

        /**
         * The endpoint the setup screen suggests when there is nothing saved.
         *
         * It is a shape, not a service: `api.lumen.app` is not running, and a
         * run goes to whatever endpoint the user typed. Any endpoint that
         * answers `POST {base}/chat/completions` in the chat-completions shape
         * will do.
         */
        public const val DEFAULT_CLOUD_URL: String = "https://api.lumen.app/v1"

        /**
         * The model the setup screen suggests when there is nothing saved.
         *
         * The model id a run actually uses is the one in its goal: the id is
         * pinned per run so that a provider-side swap cannot silently change
         * the agent's behaviour.
         */
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

        /** Nominal temperature when no thermal status is reported. */
        private const val THERMAL_NORMAL_CELSIUS: Double = 30.0

        /** Approximate Celsius at THERMAL_STATUS_LIGHT. */
        private const val THERMAL_LIGHT_CELSIUS: Double = 38.0

        /** Approximate Celsius at THERMAL_STATUS_MODERATE. */
        private const val THERMAL_MODERATE_CELSIUS: Double = 41.0

        /** Approximate Celsius at THERMAL_STATUS_SEVERE. */
        private const val THERMAL_SEVERE_CELSIUS: Double = 44.0

        /** Approximate Celsius at THERMAL_STATUS_CRITICAL. */
        private const val THERMAL_CRITICAL_CELSIUS: Double = 47.0

        /** Approximate Celsius at THERMAL_STATUS_EMERGENCY. */
        private const val THERMAL_EMERGENCY_CELSIUS: Double = 50.0

        /** Approximate Celsius at THERMAL_STATUS_SHUTDOWN. */
        private const val THERMAL_SHUTDOWN_CELSIUS: Double = 55.0
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
