# TECHSTACK.md — LUMEN

**Status:** Draft for review · **Spec version:** 1.0 · **Codename:** LUMEN (placeholder)

Every version here is a **lock**, not a suggestion. Upgrades are ADR-worthy. Every third-party dependency is marked with its approval state — nothing lands in a build file without explicit user approval (`AGENTS.md` §4).

---

## 1. Platform baseline

| Item | Version | Rationale |
|---|---|---|
| Compile / target SDK | 35 (Android 15) | `FOREGROUND_SERVICE_SPECIAL_USE`, `MediaProjection` per-use consent, modern a11y APIs |
| Min SDK | 26 (Android 8.0) | Foreground service types, `canTakeScreenshot` (API 30) guarded at runtime; VirtualDisplay APIs are all API 26+ |
| JDK | 17 | AGP 8.x requirement; the toolchain does not need 21 |
| Kotlin | 2.0.21 | K2 compiler; required by Compose Compiler 2.x |
| Gradle | 8.9 | AGP 8.7 compatibility |
| Android Gradle Plugin | 8.7.3 | — |
| Compose Compiler | 2.0.21 | Must match the Kotlin version exactly |
| Compose BOM | 2024.10.01 | Pins Compose runtime/material3/ui |
| Room | 2.6.1 | KSP-supported; stable schema migration API |
| Coroutines | 1.9.0 | `kotlinx-coroutines-android` |
| DataStore | 1.1.1 | Preferences only (settings, pin, allowlist) |
| WorkManager | 2.9.1 | Keepalive job only |
| Lifecycle / ViewModel Compose | 2.8.6 | — |
| Navigation Compose | 2.8.3 | Console navigation |
| OkHttp | 4.12.0 | Control + bulk clients, WebSocket |
| Kotlinx Serialization | 1.7.3 | JSON + proto-lite payloads; multiplatform-safe |
| KSP | 2.0.21-1.0.28 | Room + serialization codegen |

**Tooling (build-time only, never shipped):** ktlint 1.3.1, detekt 1.23.6, Android Lint (AGP built-in), JUnit 4.13.2, kotlin-test 2.0.21, MockK 1.13.13, Robolectric 4.13, kotlinx-coroutines-test 1.9.0, Truth 1.4.4.

---

## 2. Module layout

```kotlin
// android/settings.gradle.kts
include(":core:model", ":core:agent", ":core:brain", ":core:policy",
        ":core:quota", ":core:sandbox", ":core:store", ":core:protocol",
        ":core:grounding", ":core:action",
        ":platform:accessibility", ":platform:capture",
        ":platform:virtualdisplay", ":platform:idle", ":platform:overlay",
        ":agent", ":app", ":console-web")
```

`:core:*` are `java-library` + `kotlin("jvm")` modules with **no** Android plugin. This is the mechanism that makes the agent loop testable on the JVM without Robolectric.

```kotlin
// android/core/model/build.gradle.kts
plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3") }
```

---

## 3. UI stack — the 3D Liquid Glass pipeline

### 3.1 Why Compose + AGSL

`D3` locks Kotlin + Jetpack Compose + AGSL. The requirement is a *refractive* material — light bending through a frosted layer, not a translucent rectangle. That needs a fragment shader with a noise-distorted backdrop sample, a Fresnel-weighted specular rim, and a chromatic split at the edges. AGSL (`RuntimeShader`) gives exactly that on a `RenderEffect`/`GraphicsLayer` surface.

**Rejected alternatives:**

| Option | Why not |
|---|---|
| Flutter + Impeller | Highest RAM cost of the candidates against a 2 GB cap; the A11y/MediaProjection bridge still needs a Kotlin platform channel, so we pay for two rendering stacks |
| Capacitor/Ionic + WebGL | The reference stack; WebView glass (`backdrop-filter`) cannot do true refraction or per-pixel chromatic aberration, and the WebView is a large fixed RAM cost |
| Compose without shaders | Fails `D3` explicitly — a Compose-only "glass imitation" is not acceptable |

### 3.2 The glass material

Three layers, composited per surface:

1. **Blur layer** — `Modifier.graphicsLayer { renderEffect = BlurEffect(radius, TileMode.Decal) }` applied to a *copy* of the backdrop, radius animated from 0 → 24 dp on entry.
2. **Refraction layer** — an AGSL `RuntimeShader` on a `Canvas`, sampling the blurred backdrop with a normal derived from a low-frequency noise field, displacing UVs by up to 3.5 dp, and splitting RGB by ±0.6 dp at the rim.
3. **Specular layer** — a `Brush.linearGradient` rim stroke with a moving highlight band, plus a `shadow` (ambient occlusion) that grounds the surface in the scene.

```kotlin
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    tint: GlassTint = GlassTint.Default,
    refractionStrength: Float = 1f,
    cornerRadius: Dp = 28.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val shader = remember { RuntimeShader(GLASS_REFLECTION_SHADER) }
    // ... blur backdrop, drive uniforms from a spring animation, draw rim
}
```

Shader source lives in `app/src/main/assets/shaders/glass_refraction.frag` and is asserted against a golden in `STYLE_GUIDE.md` §7.

### 3.3 Animation

| Motion | Curve | Duration |
|---|---|---|
| Surface entry | `spring(dampingRatio = 0.78, stiffness = 320)` | ~380 ms settle |
| Surface exit | `spring(dampingRatio = 0.9, stiffness = 420)` | ~240 ms |
| Refraction uniform | linear interpolation driven by the same spring | — |
| Indicator pulse (Mode A) | continuous, 0.25 Hz idle / 2 Hz awaiting approval | — |
| Status dot (Mode B) | `spring` scale 1.0 → 1.18 | 900 ms per cycle |

`STYLE_GUIDE.md` holds the exact token values.

### 3.4 System overlay vs in-app surface

| Surface | Mechanism | Used for |
|---|---|---|
| Indicator | Full-screen `WindowManager` overlay window, `TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_NOT_FOCUSABLE`, `FLAG_LAYOUT_NO_LIMITS` | Mode A / B indicator, visible over any app |
| Approval card | Same overlay host, `FLAG_NOT_FOCUSABLE` removed when it needs input | In-context HITL card |
| Console | `Activity` with `Compose` content | Full console, plan graph, budgets |

Using `TYPE_ACCESSIBILITY_OVERLAY` (rather than `SYSTEM_ALERT_WINDOW`) keeps the overlay inside the AccessibilityService's lifecycle and avoids a second scary permission. The trade-off — the overlay dies with the service — is accepted and documented; the foreground notification remains as the always-present signal.

---

## 4. Local inference

### 4.1 Runtime

| Option | Assessment |
|---|---|
| **llama.cpp via JNI** | Best control, smallest footprint, GGUF quantisation. Adds a native dependency and an NDK build. |
| **MLC LLM (Android)** | Excellent AGSL-free performance, but the wheel is large and the model list is curated. |
| **MediaPipe LLM Inference** | Good tooling, heavier runtime than needed for a 1–3B planner. |
| **ExecuTorch** | Strong, but adds significant build complexity for this stage. |

**Decision: llama.cpp through JNI, with a `LocalBrain` adapter behind `BrainPort`.** The adapter is the only thing that knows about the runtime. `D2` requires that a local model be *optional* — the agent must work with no model installed — so the native library is a **flavor**: `localInference` and `noLocalInference`. The default build ships `noLocalInference` until the user approves the native dependency.

### 4.2 Model selection

| Role | Size | Quantisation | Resident RAM |
|---|---|---|---|
| Planner / reasoner | 1.5–3 B params | Q4_K_M | 900–1,400 MiB |
| Grounding critic | 0.5–1.5 B params | Q4_K_M | 500–900 MiB |
| VLM fallback (optional) | 2–3 B params, vision-tuned | Q4_K_M | 1,200–1,800 MiB |

A VLM and a text model do not fit simultaneously inside the 2 GB cap. If the VLM is enabled, the text model is unloaded. The router enforces this, not the user.

### 4.3 Invocation rules

- **One local inference at a time.** A `Semaphore(1)` in the `LocalBrain` adapter. Never parallel model calls.
- **Context budget:** 8,192 tokens for the planner, 4,096 for the critic. The tree snapshot is *summarised* to fit — the raw tree is never placed in the prompt verbatim.
- **Timeout:** 30 s per planner call, 15 s per critic call. A timeout escalates to cloud or fails the step; it never hangs the loop.
- **Seeding:** the sampler is seeded per step and the seed is persisted, so a re-plan of the same step with the same context is reproducible.

---

## 5. Cloud inference

| Concern | Choice |
|---|---|
| Provider | OpenAI-compatible chat completions as the primary interface, with an Anthropic adapter |
| Model pinning | The model id is pinned per role in config and recorded in the plan, so a provider-side model swap cannot silently change behaviour |
| Tool calling | Native tool-calling schema; the schema is in `API_SPEC.md` §3 |
| Streaming | Not used for planning (we need the whole structured reply); used for the console's live reasoning trace |
| Failure | Retry 3× with exponential backoff, then fall back to local, then pause the run |
| Egress | Every call carries a `runId` + `stepId`; the `ContentEgressGuard` applies to any screen-derived content in the prompt |

---

## 6. Storage engines

| Concern | Choice | Notes |
|---|---|---|
| Hot state | **Room 2.6.1** (SQLite) | Runs, plans, steps, observations, approvals, budgets, settings |
| Migrations | Room `Migration` objects, versioned, tested | Every schema change ships a migration + a migration test |
| Blobs | Files on disk under `sandbox/frames/` | Never in the DB |
| Traces | Append-only JSONL under `sandbox/traces/` | Rotating by size and age |
| Settings | **DataStore Preferences** | Small key-value: brain pin, allowlist, idle window, indicator mode |
| Model weights | `sandbox/models/` | User-installed; never auto-downloaded without consent |
| Workspace | `sandbox/workspace/` | Agent-produced files, user-visible, never auto-deleted |
| Encryption at rest | Room SQLCipher | Key in Android Keystore |

**Why SQLCipher:** the observation store contains screen content. The sandbox is app-scoped, but the data is still sensitive, and the user asked for a sandbox boundary that is meaningful. SQLCipher adds ~5% overhead and a Keystore-managed key.

**Why not a graph DB:** the "memory graph" in `D4` is a *step DAG* in Room with an adjacency table and recursive CTEs. Room + SQLite handles a 400-step graph with indices comfortably; a dedicated graph engine would cost RAM we do not have.

```sql
-- The step graph, as a table
CREATE TABLE steps (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES runs(id),
  plan_version INTEGER NOT NULL,
  phase TEXT NOT NULL,
  intent TEXT NOT NULL,
  action_json TEXT NOT NULL,
  expectation_json TEXT,
  precondition_json TEXT,
  risk_class TEXT NOT NULL,
  reason_source TEXT NOT NULL,     -- I9: the auditable reason is mandatory
  reason_text TEXT NOT NULL,       -- I9: and it is never blank
  state TEXT NOT NULL,
  depends_on_json TEXT NOT NULL,   -- adjacency list
  seed INTEGER,                    -- I8: determinism
  prompt_version TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX idx_steps_run ON steps(run_id, plan_version, state);
```

---

## 7. IPC

| Boundary | Mechanism | Rationale |
|---|---|---|
| `:app` ↔ `:agent` | **AIDL** (`IAgentRuntime.aidl`) | Bidirectional, callback-based, survives process boundaries, no extra dependency |
| `:agent` ↔ `:ui` AccessibilityService | AIDL (`IAccessibilityBridge.aidl`) | The agent must not import Accessibility APIs |
| `:agent` ↔ console backend | **OkHttp WebSocket** (`/v1/runs/{id}/stream`) | Live spectating: frames, step events, reasoning trace |
| `:agent` ↔ cloud brain | OkHttp HTTP | Request/response with tool calls |
| `:agent` ↔ MCP servers | **MCP client** (JSON-RPC 2.0 over stdio and Streamable HTTP) | Tool calling for external capabilities |

**AIDL surface** (`API_SPEC.md` §2): `startGoal`, `pauseRun`, `resumeRun`, `abortRun`, `answerApproval`, `subscribeState`, `subscribeFrames`, `getBudget`, `setBrainPin`.

**AIDL over Binder for state, not a database observer.** The database is the source of truth; the Binder stream is the fast path for the console. The console reconnects and re-reads the database if the stream drops.

---

## 8. MCP client

We are an MCP **client**, not a server. The agent may call tools exposed by user-configured MCP servers.

| Aspect | Choice |
|---|---|
| Protocol | JSON-RPC 2.0 over stdio (local servers) and Streamable HTTP (remote) |
| Library | **Hand-rolled minimal client** in `core/protocol` (~400 lines) — no third-party dependency |
| Tool discovery | `tools/list`, cached with a TTL, refreshed on demand |
| Tool invocation | `tools/call` with the run's `AbortSignal` wired to `notifications/cancelled` |
| Result handling | Text, image (base64), and structured content; images are budgeted against `FRAME_BUFFER` |
| Safety | MCP tools are subject to the same `PolicyEngine` as any action. An MCP tool that is classified H1–H9 triggers the same HITL gate |
| Sandbox | MCP server processes are launched by the *user* in their own environment, not by the agent. The agent never spawns a server (I5) |

**Why hand-rolled:** the MCP surface we need is `initialize`, `tools/list`, `tools/call`, `notifications/cancelled`. A dependency for that is not justified, and `AGENTS.md` §4 requires approval for every dependency.

---

## 9. Testing stack

| Layer | Tool | Runs on |
|---|---|---|
| Pure unit | JUnit 4 + kotlin-test + Truth | JVM |
| Coroutines | `kotlinx-coroutines-test` with a virtual-time scheduler | JVM |
| Mocking | MockK | JVM |
| Android units | Robolectric 4.13 | JVM |
| Integration | `androidx.test` + Espresso for the console | Device |
| Accessibility fakes | A synthetic `AccessibilityNodeInfo` tree builder in `core/grounding/src/test` | JVM |
| Quota probe | An on-device test that reports RSS/PSS/storage | Device |
| Benchmarks | JMH (JVM) for the planner and tree parse | JVM |
| Golden images | Compose UI screenshot tests (`androidx.compose.ui:ui-test-junit4`) | Device |

---

## 10. Dependency ledger

This section is the **machine-enforced contract**. `:dependencyLedger` fails the
build when a declared artifact does not appear below, and `:dependencyGuard`
fails the build when a declared group is not in
`android/gradle/approved-dependencies.txt` or is in
`android/gradle/pending-dependencies.txt`.

### 10.1 Android and JVM

| Artifact | Group | Module | Purpose | Approval |
|---|---|---|---|---|
| `kotlin-stdlib` | `org.jetbrains.kotlin` | all | Language runtime | **Pre-approved** |
| `kotlinx-coroutines-core` | `org.jetbrains.kotlinx` | all | Concurrency | **Pre-approved** |
| `kotlinx-coroutines-android` | `org.jetbrains.kotlinx` | `app`, `agent`, `platform:*` | Main dispatcher | **Pre-approved** |
| `kotlinx-coroutines-test` | `org.jetbrains.kotlinx` | test | Virtual time | **Pre-approved** |
| `kotlinx-serialization-json` | `org.jetbrains.kotlinx` | `core:model`, `core:protocol` | Payloads | **Pre-approved** |
| `core-ktx` | `androidx.core` | `app`, `agent`, `platform:*` | AndroidX core | **Pre-approved** |
| `appcompat` | `androidx.appcompat` | `app` | Compatibility | **Pre-approved** |
| `lifecycle-runtime-ktx`, `lifecycle-viewmodel-ktx`, `lifecycle-viewmodel-compose`, `lifecycle-service` | `androidx.lifecycle` | `app`, `agent` | Lifecycle | **Pre-approved** |
| `activity-compose` | `androidx.activity` | `app` | Compose host | **Pre-approved** |
| `navigation-compose` | `androidx.navigation` | `app` | Console navigation | **Pre-approved** |
| `compose-bom` | `androidx.compose` | `app` | Compose version alignment | **Pre-approved** (`D3`) |
| `ui`, `ui-graphics`, `ui-tooling`, `ui-tooling-preview` | `androidx.compose.ui` | `app` | Compose UI | **Pre-approved** (`D3`) |
| `runtime` | `androidx.compose.runtime` | `app` | Compose runtime | **Pre-approved** (`D3`) |
| `material3` | `androidx.compose.material3` | `app` | Material 3 | **Pre-approved** (`D3`) |
| `material-icons-extended` | `androidx.compose.material` | `app` | Icons | **Pre-approved** (`D3`) |
| `room-runtime`, `room-ktx`, `room-compiler`, `room-testing` | `androidx.room` | `core:store` | Hot state | **Pre-approved** (`D4`, ADR 0002) |
| `datastore-preferences` | `androidx.datastore` | `core:store` | Settings | **Pre-approved** (`D4`, ADR 0002) |
| `work-runtime-ktx` | `androidx.work` | `agent` | Keepalive | **Pre-approved** |
| `credentials` | `androidx.credentials` | `app` | Optional sign-in | **Pending** |
| `okhttp`, `logging-interceptor`, `mockwebserver` | `com.squareup.okhttp3` | `core:protocol`, `agent` | HTTP + WebSocket | **Pre-approved** |
| `android-database-sqlcipher`, `android-database-sqlcipher-ktx` | `net.zetetic` | `core:store` | Encrypting the observation store | **Pending** — native dependency |
| `llama.cpp` (JNI) | `org.llama` | `core:brain` | On-device inference | **Pending** — native dependency, NDK build |
| `junit` | `junit` | test | Test framework | **Pre-approved** |
| `truth` | `com.google.truth` | test | Assertions | **Pre-approved** |
| `mockk` | `io.mockk` | test | Mocking | **Pre-approved** |
| `robolectric` | `org.robolectric` | test | Android units | **Pre-approved** |
| `core`, `runner`, `rules` | `androidx.test` | androidTest | Instrumentation | **Pre-approved** |
| `espresso-core` | `androidx.test.espresso` | androidTest | UI assertions | **Pre-approved** |
| `material` | `com.google.android.material` | `app` | Material components | **Pre-approved** |

**Build-time plugins** (never shipped in an APK): `com.android.tools.build:gradle`,
`org.jetbrains.kotlin:kotlin-gradle-plugin`, `org.jetbrains.kotlin:kotlin-serialization`,
`com.google.devtools.ksp`, `org.jlleitschuh.gradle:ktlint-gradle`,
`io.gitlab.arturbosch.detekt:detekt-gradle-plugin`, `org.jetbrains.kotlin.plugin.compose`.

### 10.2 Web console (npm)

| Package | Version | Purpose | Approval |
|---|---|---|---|
| `react`, `react-dom` | 18.3.1 | Console UI | **Pre-approved** |
| `vite` | 5.4.10 | Build tool | **Pre-approved** |
| `@vitejs/plugin-react` | 4.3.3 | React plugin | **Pre-approved** |
| `typescript` | 5.6.3 | Type system | **Pre-approved** |
| `eslint`, `@eslint/js`, `typescript-eslint`, `eslint-plugin-react-hooks`, `globals` | 9.13.0 / 8.11.0 / 5.0.0 / 15.11.0 | Lint | **Pre-approved** |
| `@types/react`, `@types/react-dom` | 18.3.12 / 18.3.1 | Types | **Pre-approved** |

The web console adds **no** runtime dependency on the Android side. The frame
and event channels are plain WebSocket and fetch, so the console has no
third-party transport library.

### 10.3 Approval queue

Everything marked **Pending** requires explicit user approval before it lands in
a build file. Removing the group from
`android/gradle/pending-dependencies.txt` **is** the act of approval, and it
must be recorded in the table above. The full queue with the recommendation is
in `TASK.md` Appendix.

---

## 11. Build flavors

```kotlin
flavorDimensions += "inference"
productFlavors {
    create("noLocalInference") { dimension = "inference" }  // default
    create("localInference") {
        dimension = "inference"
        externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    }
}
```

The default build has **no** native code and no model weights. Local inference is opt-in, both at build time and at runtime, and the agent is fully functional without it (`D2`).

---

## 12. Performance budgets

| Operation | Budget | Measured by |
|---|---|---|
| Accessibility tree snapshot (500 nodes) | ≤ 40 ms | JMH + on-device probe |
| Settle detection (typical) | ≤ 300 ms | On-device probe |
| Tree summariser for the planner prompt | ≤ 20 ms | JMH |
| Plan step dispatch round trip | ≤ 250 ms | On-device probe |
| Console frame to first paint | ≤ 300 ms | Screenshot test |
| Room step transaction | ≤ 5 ms | JMH |
| Glass surface frame time | ≤ 8 ms (120 Hz) | Macrobenchmark |

Any budget missed in a release build is a defect. `quotaProbe` and the benchmark suite report against these numbers.
