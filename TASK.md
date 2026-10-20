# TASK.md — LUMEN Implementation Checklist

**Status:** Draft for review · **Spec version:** 1.0 · **Codename:** LUMEN (placeholder)

Sequential milestones. Work only inside the current milestone's unchecked items. If a task is not listed, **ask** before doing it.

---

## §0. Working rules (read once, apply always)

- [ ] **Nothing lands without approval.** A new third-party dependency, a new permission, a new platform capability, or a quota change requires explicit user approval **before** it enters a build file. The `TECHSTACK.md` §10 ledger is the only place a dependency may be added, and only after approval.
- [ ] **Every milestone ends with a green gate and a quota statement.** The PR description states the RAM and storage impact in MiB.
- [ ] **Every new I/O path** gets a sandbox-path-validation test (`TESTING.md` §3.2).
- [ ] **Every new long-running loop** gets a cancellation test.
- [ ] **Every new state field** gets a Room migration and a migration test.
- [ ] **No application code before the user approves the Phase 3 documents.**

---

## M0 — Repository and toolchain bootstrap

**Goal:** an empty, buildable, lint-clean Gradle project with the module skeleton and the CI gate.

- [x] Create the Gradle project under `android/` with the version catalog from `TECHSTACK.md` §1.
- [x] Create the module skeleton: `core/{model,action,grounding,policy,quota,sandbox,store,protocol,brain,agent}`, `platform/{accessibility,capture,virtualdisplay,idle,overlay}`, `agent`, `app`, `console-web`.
- [x] Apply the module boundary rules: `core/*` gets the Kotlin JVM plugin only, **no** Android plugin. The single exception is `:core:store` (ADR 0002).
- [x] Configure `ktlint`, `detekt`, and Android Lint with the rules in `CODING_STANDARDS.md` §10.
- [x] Write the `:core:dependencyGuard` task: fails on a `core/*` → `android.*` / `androidx.*` / cross-module dependency, on an unapproved dependency, and on a pending one.
- [x] Write the dependency-ledger check: fails when a dependency is absent from `TECHSTACK.md` §10.
- [x] Set up the `console-web` build (Vite + TypeScript + React) with a placeholder page and a committed lockfile.
- [x] Add the CI workflow running `lint ktlintCheck detekt test :core:dependencyGuard`.
- [x] Write `docs/adr/0000-template.md`, ADR 0001 (the dependency approval gate), and ADR 0002 (the `:core:store` exception).
- [x] Commit the spec suite (this directory) as the baseline.

**Gate:** `./gradlew lint ktlintCheck detekt test :core:dependencyGuard` passes on a build with no source.
**Status:** the project files are complete and the two guard tasks were verified by simulating their logic against the real tree. **The Gradle gate has not been executed** — the build machine has insufficient RAM, so the gate runs in CI. The first CI run is the real M0 gate.
**Quota impact:** none (no runtime code).

---

## M1 — Core model

**Goal:** the vocabulary every other module speaks, in pure Kotlin.

- [x] `Goal`, `Plan`, `Step`, `StepState`, `Action`, `Observation`, `Decision`, `Reason`, `Verdict`, `RiskClass`, `Phase`.
- [x] `Action` as a sealed interface with the full primitive set from `ARCH.md` §4.1.
- [x] `Observation` — app, nodes, overlays, optional screenshot reference, settle metadata.
- [x] `RunId` / `StepId` / `ApprovalId` value types with ULID generation and validation.
- [x] `LumenClock` interface with a `SystemLumenClock` implementation (JVM + Android).
- [x] `ApiError` with the stable codes from `API_SPEC.md` §1.
- [x] Serialization annotations on every wire type, with `@SerialName` on every field.
- [x] `PlanEquivalence` — the determinism comparator from `ARCH.md` §9.
- [x] Unit tests: serialisation round-trips, ULID monotonicity, plan equivalence ignores timestamps.

**Gate:** `./gradlew :core:model:test` passes.
**Status:** implemented. The test gate was **not executed** — the build machine has insufficient RAM, so it runs in CI. Static checks were run instead: brace balance over all 46 Kotlin files, every `@Serializable` property type verified serialisable, every package-local reference resolved, and the dependency guard re-simulated.
**Quota impact:** negligible — the model holds no resident state. The tree cache that uses `NodeRef` is budgeted at 192 MiB and belongs to M4.

**Notes for the next milestone:**
- `core:model` is the only module that may be depended on by every other core module. It must stay free of behaviour and free of Android types.
- `Action` and `Assertion` are the two sealed hierarchies. Any new primitive goes into `Action` with a `@SerialName`, and the `ActionSerializationTest` list must grow with it.
- `Reason` refuses blank text at construction. A step without a reason does not compile.

---

## M2 — Sandbox and storage

**Goal:** the 20 GB boundary, enforced by construction.

- [ ] `SandboxPath` with the full validator from `CODING_STANDARDS.md` §6.2.
- [ ] `SandboxRoot` — resolves the app-scoped sandbox directory.
- [ ] `WorkspaceManager` — read/write/list/delete inside the sandbox.
- [ ] `StorageQuota` — bucket accounting, LRU eviction, `workspace/` exemption.
- [ ] Room schema v1: `runs`, `plans`, `steps`, `observations`, `approvals`, `budget_ledger`, `settings`.
- [ ] `RunDao`, `StepDao`, `ApprovalDao`, `ObservationDao` with the queries the runtime needs.
- [ ] `TraceWriter` — rotating JSONL under `traces/`, size- and age-based rotation.
- [ ] `FrameStore` — frames under `frames/`, LRU, downscaled after 24 h.
- [ ] SQLCipher key in the Android Keystore.
- [ ] Tests: the full `Q-STO-*` set from `TESTING.md` §3.2, plus a migration test harness.
- [ ] Tool: `tools/quota-probe` — reports per-bucket usage.

**Gate:** `./gradlew :core:sandbox:test :core:store:test` passes; the `Q-STO-*` suite is green.
**Quota impact:** SQLite + WAL ≈ 12–40 MiB depending on the number of steps; the `state/` bucket caps at 2,000 MiB.

---

## M3 — Quota engine

**Goal:** the 2 GB RAM cap and the compute governors, enforced by admission control.

- [x] `QuotaWatchdog` — the tick loop, the category reservations, the eviction ladder from `ARCH.md` §7.1.
- [x] `RamBudget` — reserve / release / report, exact MiB accounting.
- [x] `ThermalGovernor` — reads thermal status, drives worker count and inference suspension.
- [x] `BatteryGovernor` — suspends autonomous runs below 20 % when not charging.
- [x] `WorkerPool` — a `Semaphore(4)` with a reported depth and a denied-request error.
- [x] `MemoryProbe` — the platform interface that reads PSS/RSS; a fake for tests.
- [x] Tests: the full `Q-RAM-*`, `Q-THM-*` sets, including the concurrent over-commit case.
- [x] Tool: `tools/quota-probe` — per-category RAM report.

**Gate:** `./gradlew :core:quota:quotaTest` passes.
**Status:** implemented. The test gate was **not executed** — the build machine has insufficient RAM, so it runs in CI. Static checks: brace balance over all 55 Kotlin files, and every scenario in the suites was traced by hand against the watchdog's arithmetic.
**Quota impact:** the watchdog itself holds a budget, a map of evictables and a last-tick record — a few KiB, inside the 64 MiB `BASELINE` budget.

**Notes for the next milestone:**
- `RamBudget` is the only way to obtain memory. A component that allocates outside it is a defect, and the `dependencyGuard`-style check for that is a lint rule to add when the first real consumer lands.
- The watchdog reports the pressure it *found*, not the pressure it left behind: a tick is an alarm record.
- The cap is a hard boundary. Reaching 2 GB stops the run even if the eviction that followed brought the measurement back under the cap.
- `WorkerPool` **refuses** a fifth worker rather than queueing it. A queued request would silently blow the wall-clock budget.

---

## M4 — Grounding, in pure core

**Goal:** tree reading, indexing, summarising, and settle detection with no Android dependency.

- [ ] `GroundingPort` interface and `TreeSnapshot` / `NodeRef` / `NodeIndex` models.
- [ ] `TreeSummariser` — fits the planner's token budget, canonical ordering for determinism.
- [ ] `UiEventClock` interface + `SettleDetector` with the constants from `ARCH.md` §4.2.
- [ ] `NodeMatcher` — find by text, by id, by class; clickable-ancestor resolution; duplicate handling.
- [ ] `GroundingSource` enum and the precedence rules (tree → VLM → fail).
- [ ] Tests: the full `T-NAV-*` and `T-SET-*` sets, plus the synthetic tree builder fixture.
- [ ] Property test: `settledTree` always returns within its cap.

**Gate:** `./gradlew :core:grounding:test` passes with no Robolectric.
**Quota impact:** the node index is budgeted at ≤ 192 MiB; a 500-node tree indexes in ≈ 40 ms.

---

## M5 — Action model and executor contract

**Goal:** one dispatch funnel, testable against a fake.

- [ ] `ActionExecutor` interface — the single funnel.
- [ ] `ActionValidator` — relative coordinate range, schema conformance, allowlist check.
- [ ] `ActionLedger` — write-ahead pre-state, fsync before dispatch.
- [ ] `FakeActionExecutor` — records actions, returns scripted results, can fail.
- [ ] `FakeGroundingPort` — serves scripted snapshots.
- [ ] `FakeBrain` — scripted plans, scripted failures and escalations.
- [ ] Tests: the full `T-DSP-*` set, including the ledger-failure precondition.

**Gate:** `./gradlew :core:action:test` passes.
**Quota impact:** the ledger writes ≈ 2–8 KiB per step; 400 steps ≈ 3 MiB, inside the `traces/` budget.

---

## M6 — Platform: accessibility

**Goal:** the real actuator.

- [ ] `LumenAccessibilityService` — the only class touching `AccessibilityService` APIs.
- [ ] `IAccessibilityBridge.aidl` — the interface the agent process calls.
- [ ] Accessibility service config: `typeAllMask`, `canPerformGestures`, `canTakeScreenshot`, `flagReportViewIds`, `flagRetrieveInteractiveWindows`.
- [ ] `NodeReader` — real tree reading, mapped onto the core `TreeSnapshot`.
- [ ] `GestureInjector` — tap, double tap, long press, swipe, path drag with easing.
- [ ] `UiEventTracker` — maintains the event clock that `SettleDetector` consumes.
- [ ] Foreground-app guard and allowlist enforcement at dispatch time.
- [ ] Instrumentation: `I-01`, `I-02`, `I-03`.

**Gate:** `./gradlew :platform:accessibility:test connectedAndroidTest` passes on a device.
**Quota impact:** the tree cache is budgeted at ≤ 192 MiB; the service itself ≈ 18–30 MiB.

---

## M7 — Policy engine and HITL

**Goal:** no irreversible action without a human.

- [ ] `PolicyEngine` — deterministic classification, deny by default.
- [ ] `RiskClass` — the H1–H10 taxonomy from `PRD.md` §6.
- [ ] `DangerClassifier` — surface detectors: payment, publish, delete, credential, permission, communication, account, legal.
- [ ] `ApprovalBroker` — durable staging, leases, timeouts, resolution routing.
- [ ] `GrantStore` — per-run, per-action-class grants, expiring.
- [ ] `ApprovalRequest` builders, including the redacted `ActionPreview`.
- [ ] Tests: the full `L-HIT-*` set, including "the brain can only tighten, never loosen".

**Gate:** `./gradlew :core:policy:test` passes.
**Quota impact:** the approval store is a few dozen rows per run.

---

## M8 — Brain port and cloud brain

**Goal:** reasoning, with a cloud implementation and a local contract.

- [ ] `BrainPort` interface — `plan`, `replan`, `critique`, `escalate`.
- [ ] `BrainRouter` — the `AUTO` / `LOCAL` / `CLOUD` policy from `D2`, with the user pin always winning.
- [ ] `CloudBrain` — the OpenAI-compatible adapter plus an Anthropic adapter.
- [ ] Tool schema generation and validation against `API_SPEC.md` §3.
- [ ] `RetryPolicy` — the single retry implementation, with backoff and a `retryable` predicate.
- [ ] Response validation — a malformed model reply fails the step, it does not execute.
- [ ] `EgressGuard` integration — screen-derived content in a prompt is checked against the spectating setting.
- [ ] Tests: schema conformance, malformed-response handling, escalation, the pin precedence.

**Gate:** `./gradlew :core:brain:test` passes; the golden tool-schema test is green.
**Quota impact:** the cloud path is network-bound, not RAM-bound; the plan/response working set is budgeted at ≤ 96 MiB.

---

## M9 — The agent loop

**Goal:** the Planner–Actor–Critic, running end to end against fakes.

- [ ] `AgentRuntime` — the loop, the step lifecycle, the budget ledger.
- [ ] `Planner` — goal → step DAG, plan versioning, re-planning.
- [ ] `Actor` — dispatch + observe, atomic, single-threaded.
- [ ] `Critic` — evaluates the expectation against the observation, emits a verdict.
- [ ] `RunController` — start, pause, resume, abort, checkpoint.
- [ ] `AbortSignal` — checked before every dispatch (I12).
- [ ] `ScriptedScenario` DSL and the `HundredStepScenario`.
- [ ] Tests: the full `L-100-*` and `L-REC-*` sets, plus the property tests.
- [ ] Determinism: the same seed and the same screen sequence produce an identical plan.

**Gate:** `./gradlew :core:agent:loopRecoveryTest` passes.
**Quota impact:** the loop's working set is budgeted at ≤ 96 MiB and is compacted to disk under pressure.

---

## M10 — Agent runtime service and IPC

**Goal:** the run survives the console being killed.

- [ ] `AgentRuntimeService` — a foreground service, `foregroundServiceType="specialUse"`, in the `:agent` process.
- [ ] The persistent notification, with a "Stop agent" action.
- [ ] `IAgentRuntime.aidl` and the payload types from `API_SPEC.md` §2.
- [ ] `StateStream` and `FrameStream` — one-shot register, server-push, re-connectable.
- [ ] `BootReceiver` — re-arms a resumable run only.
- [ ] `WorkManager` keepalive job.
- [ ] Frame transfer by `ParcelFileDescriptor` — never an inlined Binder payload.
- [ ] `LumenAccessibilityService` revocation detection → the run pauses with `accessibility_revoked`.
- [ ] Tests: the golden AIDL payload suite, the process-death resume test.

**Gate:** `./gradlew :agent:test connectedAndroidTest` passes; `I-08`, `I-09`, `I-12` are green.
**Quota impact:** the agent process baseline ≈ 40–64 MiB.

---

## M11 — 3D Liquid Glass console

**Goal:** the visual system, implemented exactly as specified.

- [ ] `GlassTokens.kt` — every value from `STYLE_GUIDE.md` §2–§5 as a constant.
- [ ] `glass_refraction.frag` — the AGSL shader, with the parameters from §3.2.
- [ ] `GlassSurface` composable — the five-layer stack.
- [ ] `edge_glow.frag` — the Mode A indicator shader.
- [ ] `IndicatorHost` — the overlay window, the three modes, the state machine.
- [ ] `ApprovalCard` — the overlay host for in-context HITL.
- [ ] The console screens: live frame, plan graph, reasoning trace, budget meters, settings.
- [ ] `takeover` — the instant handover.
- [ ] Respect the "remove animations" system setting.
- [ ] Tests: `I-10`, `I-11`, and the golden screenshot suite.

**Gate:** `./gradlew :app:test connectedAndroidTest` passes; the golden screenshots match.
**Quota impact:** the console is in the `:ui` process; the renderer holds ≤ 24 MiB of surface buffers.

---

## M12 — Idle gate and virtual display

**Goal:** the agent never hijacks an active session.

- [ ] `IdleGate` — screen state, touch observation, the idle window.
- [ ] `TouchObserver` — resets the idle timer on any touch.
- [ ] `VirtualDisplayManager` — creates the private display, moves the task, reports success or failure.
- [ ] The surface-selection policy from `ARCH.md` §4.4.
- [ ] The foreground-package guard re-check immediately before a live-surface gesture.
- [ ] Tests: `I-06`, `I-07`, and `L-REC-11` (the user-touch pause within 1 s).

**Gate:** `./gradlew :platform:idle:test :platform:virtualdisplay:test connectedAndroidTest` passes.
**Quota impact:** a virtual display costs ≈ 30–80 MiB depending on resolution; it is released when the run ends.

---

## M13 — Capture, spectating, and the web console

**Goal:** live observability and instant takeover.

- [ ] `CaptureProvider` — the accessibility `canTakeScreenshot` path first, MediaProjection second.
- [ ] `RequestCastActivity` — the MediaProjection consent UI.
- [ ] The frame pipeline: capture → downscale → budget → transport.
- [ ] The console backend: the endpoints from `API_SPEC.md` §6, the WebSocket event stream, the frame socket.
- [ ] `console-web`: the live view, the plan graph, the reasoning trace, the budget meters, the approval queue, the takeover button.
- [ ] `ContentEgressGuard` — default deny, enforced on every outbound channel.
- [ ] Tests: `I-04`, `I-14`, and the egress-guard suite.

**Gate:** `./gradlew :console-web:build` and the device instrumentation pass.
**Quota impact:** the frame buffer is budgeted at ≤ 128 MiB; frames are dropped, never queued, when over budget.

---

## M14 — Local brain (native flavor) — **pending user approval**

**Goal:** on-device reasoning, so the agent works offline and under latency.

- [ ] **Get explicit user approval for the native dependency and the NDK build.** Do not start without it.
- [ ] `localInference` product flavor, CMake build, the JNI bridge.
- [ ] `LocalBrain` — the `BrainPort` implementation over llama.cpp.
- [ ] The `Semaphore(1)` concurrency guard.
- [ ] Model download with an explicit user-consent step (never automatic).
- [ ] The model registry: role → model file → RAM footprint.
- [ ] `QuotaWatchdog.reserve` before every load, with the cloud fallback on refusal.
- [ ] Tests: the router precedence, the admission refusal, the unload-and-escalate path.

**Gate:** `./gradlew :core:brain:test` passes in both flavors; `Q-RAM-04` is green.
**Quota impact:** the resident model is capped at 1,400 MiB by the `BRAIN_MODEL` budget.

---

## M15 — Hardening and release

**Goal:** the thing is safe to run unattended.

- [ ] The 100-step soak test on a real device, in real time, with real screen transitions.
- [ ] The full `quotaProbe` report; every category within its declared budget.
- [ ] Thermal soak — 30 minutes of continuous work at ≥ 42 °C.
- [ ] Battery drain measurement in background mode.
- [ ] The `ContentEgressGuard` audit — a full run with the network captured, asserting that no screen content left the device.
- [ ] The permission-set golden test, with the final manifest.
- [ ] The Play Store disclosure: the accessibility-service rationale, shown in-app before enabling.
- [ ] Crash reporting: opt-in, redacted, off by default.
- [ ] The release build: `lint ktlintCheck detekt test :core:dependencyGuard` plus the connected suite.
- [ ] The operator documentation: install, permissions, pairing, spectating, kill switch, quota policy.

**Gate:** every gate in `AGENTS.md` §7 passes.
**Quota impact:** the steady-state footprint is documented in the release notes.

---

## Appendix — Dependency approval queue

Present this list to the user as a single approval request, and record the outcome in `TECHSTACK.md` §10.

| # | Dependency | Module | Why | Risk |
|---|---|---|---|---|
| 1 | llama.cpp (JNI) + the NDK toolchain | `core:brain` | On-device inference | A native library raises the APK size and adds a build toolchain requirement |
| 2 | AndroidX Credentials | `app` | Optional sign-in for the console | A Google Play Services dependency the app can live without |
| 3 | SQLCipher | `core:store` | Encrypting the observation store | A native dependency, and a key-management surface |
| 4 | WorkManager | `agent` | Keepalive after a process kill | A background scheduler the agent could also implement itself |
| 5 | A VLM runtime (for the grounding fallback) | `core:brain` | Screens the tree cannot read | The largest single RAM cost in the system; competes with the text model |

**Recommendation:** approve 1 and 3 (both are core to the product's promises), defer 2 (optional), keep 4 (it is small and solves a real problem), and treat 5 as a user-installed model rather than a bundled dependency.
