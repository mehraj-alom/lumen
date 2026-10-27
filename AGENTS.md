# AGENTS.md — LUMEN (Mobile Operator Agent)

> **Codename `LUMEN` is a placeholder.** Rename freely; it appears in package ids, channel names, and DB names. Keep it consistent when you do.

**LUMEN** is an autonomous, sandboxed Android Mobile Operator Agent. It executes long-horizon workflows (15–100+ chained actions) on a real device from a single high-level intent, pauses for human confirmation before irreversible actions, and renders its own state as 3D Liquid Glass.

This file is the **binding contract for any agent or human working in this repository.** If a change conflicts with this file, the change is wrong until this file is amended deliberately.

---

## 0. Non-negotiable invariants

These 12 rules outrank every other instruction, file, or request in this repo. Violating any of them is a build-breaking defect, not a style issue.

| # | Invariant | Enforcement |
|---|---|---|
| I1 | **RAM ≤ 2 GB** for the entire agent runtime, memory graph, and all background workers. | `QuotaWatchdog` — see `ARCH.md` §7. Hard kill at 2 GB. |
| I2 | **Storage ≤ 20 GB** inside the app-scoped sandbox. Nothing outside the sandbox is read, written, or leaked without explicit user permission. | `SandboxPath` + `StorageQuota`; every file op goes through the validator. |
| I3 | **Never hijack an active touch session.** No gesture injection on the live screen while the user is actively using the device. | `IdleGate` + `VirtualDisplayManager`. See `ARCH.md` §4. |
| I4 | **HITL is mandatory** for every irreversible or sensitive action class, even if the planner believes it is safe. | `PolicyEngine` deny-by-default. No bypass flag exists. |
| I5 | **The agent never executes code it wrote on a previous run.** Self-modifying behaviour is out of scope. | Static analysis is not a supported path. |
| I6 | **Every action is observable and reversible-logged.** No action executes without a recorded pre-state. | `ActionLedger` — write-ahead, fsync before dispatch. |
| I7 | **No proprietary code, assets, or text is copied from the reference APK.** Patterns only. | `RE_ANALYSIS.md` is the only permitted source. See §5. |
| I8 | **Determinism where possible.** Same goal + same device state ⇒ same plan, modulo timestamps. | Seeded RNG, pinned planner prompt version. |
| I9 | **The agent may never act on the user's behalf without an auditable reason.** | `Reason` is mandatory on every step, logged with the action. |
| I10 | **Quotas are enforced, not monitored.** Exceeding a quota is impossible by construction, not detected after the fact. | Pre-flight admission checks + watchdog backstop. |
| I11 | **No network call leaves the device carrying screen content unless the user has enabled spectating.** | `ContentEgressGuard`. Default deny. |
| I12 | **User can always take over instantly.** The agent must be abortable within one action boundary. | `AbortSignal` checked before every dispatch. |

---

## 1. Locked architecture decisions (Phase 2 — do not relitigate without an explicit ADR)

| ID | Decision | Status |
|---|---|---|
| **D1** | **Execution isolation: hybrid.** Live-screen surface is permitted **only** behind a strict idle gate + package allowlist; opportunistically offload to a private `VirtualDisplay` when the target app supports secondary displays. | Locked |
| **D2** | **Brain routing: adaptive + user-selectable.** A `BrainRouter` picks **local** inference when a qualifying on-device model is installed *and* fits the remaining RAM budget, otherwise **cloud**. The user may pin `LOCAL` / `CLOUD` / `AUTO` at any time; a pin always wins. | Locked |
| **D3** | **UI: Kotlin + Jetpack Compose + AGSL** (`RuntimeShader`) + `RenderEffect` blur-behind. No Flutter, no WebView shell, no Compose-only "glass imitation" without shaders. | Locked |
| **D4** | **Storage: Room (SQLite) hot state + rotating tiered file logs** (frames, traces, artifacts) with LRU eviction against the 20 GB cap. | Locked |
| **D5** | **Topology: on-device agent runtime + separate web console** for spectating. The user may switch to the fully-on-device variant; each variant ships with its risk callout. | Locked (user-selectable) |
| **D6** | **Reference patterns adopted** from `RE_ANALYSIS.md` §6: single dispatch funnel, atomic action+observation, event-driven settle detection, relative coordinates, split control/bulk transport, dual HITL surfaces, non-blocking approval staging, device-state reporting, package allowlist, cancellable in-flight transport, runId-idempotent indicator, accessibility `canTakeScreenshot`. | Locked |

---

## 2. Hard quotas — the numbers

Any change to a number below requires an ADR and a `TESTING.md` update.

### RAM — 2,048 MiB total

| Consumer | Budget | Eviction behaviour |
|---|---|---|
| Local brain model (quantised resident) | ≤ 1,400 MiB | Unloaded → route to cloud |
| Accessibility tree cache + node index | ≤ 192 MiB | LRU eviction |
| Frame / screenshot buffers | ≤ 128 MiB | Drop oldest, stop capture |
| Planner + Critic working memory | ≤ 96 MiB | Compact plan to disk |
| Services, watchdog, IPC baseline | ≤ 64 MiB | — |
| Reserved headroom | ≥ 168 MiB | — |

**Admission control:** before a local model is loaded, `QuotaWatchdog.reserve(bytes)` must succeed or the router escalates to cloud. A model is never loaded "and we'll see".

### Storage — 20,000 MiB in the app-scoped sandbox

| Bucket | Budget | Policy |
|---|---|---|
| `models/` | 4,000 MiB | Model weights, user-installed |
| `frames/` | 6,000 MiB | Rotating, LRU, downscaled after 24 h |
| `traces/` | 3,000 MiB | JSONL, rotating by size + age |
| `state/` (Room + WAL + checkpoints) | 2,000 MiB | Compacted, checkpoint-pruned |
| `workspace/` | 4,000 MiB | Agent-produced files, user-visible |
| Headroom | 1,000 MiB | — |

### Compute

| Dimension | Limit |
|---|---|
| Agent worker threads | ≤ 4 |
| Concurrent local inference | 1 (never parallel model calls) |
| Wall-clock per run | ≤ 45 min default, ≤ 4 h hard ceiling |
| Steps per run | ≤ 150 soft, 400 hard |
| Thermal throttle | ≥ 42 °C → drop to 1 worker; ≥ 46 °C → suspend local inference, cloud only |
| CPU governor | Nice the agent process; never `SCHED_FIFO` |
| Battery guard | Below 20 % and not charging → suspend autonomous runs |

**Which of these are enforced today.** The RAM, storage, step and wall-clock limits are enforced by construction: admission control in `QuotaWatchdog`, `SandboxPath` + `StorageQuota` on every file operation, and `RunConfig` checked in the loop. The two rows that depend on a device reading are **not wired to anything yet**: `ThermalGovernor` and `BatteryGovernor` exist in `:core:quota` with unit tests over their arithmetic, and no production code constructs them, reads a thermal status or a battery level, or acts on a decision. A run therefore does not slow down when the phone is hot and does not stop when the battery drops — `TASK.md` M15 carries the item. `I10` is the rule that this gap violates, and until it closes the thermal and battery rows above are a specification, not a guarantee.

---

## 3. Repository layout

```
/
├── AGENTS.md              # this file — binding contract
├── CLAUDE.md              # pointer + critical rules
├── PRD.md                 # product requirements, user stories, HITL gates
├── ARCH.md                # architecture, Mermaid diagrams, quota engine
├── TECHSTACK.md           # locked versions, shader pipeline, storage engines
├── API_SPEC.md            # IPC, LLM/VLM tool schemas, MCP, streaming
├── STYLE_GUIDE.md         # 3D Liquid Glass tokens, indicator specs
├── CODING_STANDARDS.md   # typing, disposal, cancellation, path validation
├── TESTING.md             # quota, mock-tree, loop-recovery test specs
├── TASK.md                # milestone implementation checklist
├── RE_ANALYSIS.md         # clean-room reference analysis (Phase 1, frozen)
├── android/               # the Gradle project — the only buildable tree
│   ├── app/                # console UI (Compose), process: :ui
│   ├── agent/              # runtime, planner/actor/critic, process: :agent
│   ├── core/               # pure-Kotlin: model, brain, policy, quota, sandbox, store
│   ├── platform/           # accessibility, capture, virtualdisplay, idle
│   ├── console-web/        # web spectating console (D5) — an npm project, not a Gradle module
│   └── tools/              # adb scripts: quota-probe.sh, soak.sh, generate-modules.sh
└── docs/adr/              # Architecture Decision Records
```

**Module boundary rule:** `core/*` is **pure Kotlin/JVM** — no `android.*` imports, no framework types in public APIs. `platform/*` is the only place Android APIs live. This is what makes the loop testable on the JVM.

**One named exception (ADR 0002):** `:core:store` is an Android library, because Room is an Android-only artifact. The persistence *port* (`StateStore`) and every DTO live in `:core:model`, which stays pure, so `:core:agent` — the loop — remains a plain JVM module with a fake store, and `:core:agent:test` needs no Robolectric. `:core:store` may depend on `androidx.room`, `androidx.datastore` and `androidx.sqlite` and nothing else outside `core/`.

---

## 4. Build, test, lint commands

**These are CI's commands, not this workstation's.** See §8: nothing is built here, and `ci.yml` runs the gate in one Gradle invocation so a run costs what it checks.

```bash
# Every Gradle command below runs from `android/`, the Gradle root.

# Build
./gradlew :app:assembleDebug          # the console APK (the only application module)
./gradlew :agent:assembleRelease      # the agent runtime AAR (a library module)
cd console-web && npm run build       # the web spectating console (npm, not Gradle)

# Test
./gradlew test                        # all JVM unit tests, every variant
./gradlew :core:agent:test            # the loop: AgentRuntimeTest, SoakTest, HundredStepScenarioTest
./gradlew :core:quota:test            # QuotaWatchdogTest, RamBudgetTest, the two governors, WorkerPoolTest
./gradlew :core:sandbox:test          # path validation and bucket accounting
./gradlew connectedDebugAndroidTest   # instrumentation (device or emulator required)

# Static analysis
./gradlew lint                        # Android Lint, errors fail the build
./gradlew ktlintCheck detekt          # Kotlin style + complexity
./gradlew dependencyGuard dependencyLedger   # the two dependency ledgers

# Device reports (adb + a connected device; they read only)
tools/quota-probe.sh                  # live RAM and storage against the §2 budgets
tools/soak.sh 30                      # the 30-minute thermal and battery soak

# Full gate (what CI runs, in the one invocation ci.yml uses)
./gradlew lint ktlintCheck detekt test dependencyGuard dependencyLedger :app:assembleDebug --continue
```

**There is no benchmark task.** `TECHSTACK.md` §9 names JMH and Macrobenchmark as the tools and the performance budgets list what they are meant to check, but no JMH or benchmark dependency is declared in the catalog or in any module and no `jmh` or `benchmark` task is registered; §4 used to list `:core:jmh`, `:core:benchmark` and `:core:quotaProbe` as commands, and asking for any of them fails the build before it checks anything. The budgets are measured on a device, when that work is scheduled.

**The two guard tasks are root-project tasks** — `:dependencyGuard`, `:dependencyLedger`, registered in `android/build.gradle.kts`. `:core:dependencyGuard` is not a task; `:core` is not even a project, because `settings.gradle.kts` includes the leaf modules (`:core:model` … `:core:agent`) and no aggregate.

**Dependency rule:** adding any third-party dependency requires explicit user approval *before* the dependency lands in a build file. See `TASK.md` §0.

---

## 5. Clean-room rules (non-negotiable, I7)

`RE_ANALYSIS.md` is the **only** permitted source of design input from the reference APK.

- **Permitted:** architectural patterns, capability sets, method/vocabulary shapes, permission postures, algorithmic ideas at the level of "settle detection uses an event-quiet window".
- **Forbidden:** copied source, copied string resources, copied drawables, copied assets, copied iconography, copied private API names, or verbatim decompiled code.
- **Required:** any pattern we adopt must be re-implemented from the spec in `ARCH.md`, and the spec must be written in our own vocabulary (`Action`, `Observation`, `Step`, `SettleDetector`, … — not the reference's).
- Our public API names must not collide with the reference's internal names. `LumenAccessibilityService` — not `MelayaAccessibilityService`.

---

## 6. Working rules for agents

### 6.1 Before you touch code

1. Read `ARCH.md` for the component you are changing and `CODING_STANDARDS.md` for the rules.
2. Check `TASK.md` — work only inside the current milestone's unchecked items. If your task is not there, **ask**; do not silently expand scope.
3. If a task requires a **third-party dependency**, a **new permission**, a **new platform capability**, or a **quota change** → stop and ask the user. This is the Phase 4 gate.

### 6.2 While you work

- **Kotlin strict mode, no `!!`, no `lateinit` on non-null lifecycle refs.** Use `lateinit` only for `ViewModel`-scoped Android refs and always null-check before use.
- **Structured concurrency only.** No `GlobalScope`, no `Thread { }` for app logic, no `AsyncTask`. Coroutines are structured into the run scope.
- **Every `close()` has an owner.** Implement `Closeable`/`AutoCloseable` and close via `use {}` or a scoped `finally`. Frames, cursors, cursors of `Cursor`, `ParcelFileDescriptor`, and file handles are the usual offenders.
- **All file I/O goes through `SandboxPath`.** Never call `File(...)` with a raw string in app code outside `core/sandbox`.
- **All screen access goes through `GroundingPort`.** Never touch `AccessibilityService` APIs directly from business logic.
- **All inference goes through `BrainPort`.** Never call a model runtime directly.
- **No `println`.** Use the structured logger with a run/step correlation id.

### 6.3 Before you ship

- [ ] `./gradlew lint ktlintCheck detekt test` passes.
- [ ] New logic has a unit test in the module that owns it (not a shared test module).
- [ ] Any new I/O path is covered by a sandbox-path-validation test.
- [ ] Any new long-running loop has a cancellation test.
- [ ] Any new state field is added to the Room schema **with a migration** and the migration is tested.
- [ ] Quota impact of the change is stated in the PR description.
- [ ] The change does not add a dependency, permission, or capability without explicit user approval.

### 6.4 Language

- Prose in docs and code comments: plain, specific, no marketing adjectives, no "simply", "just", "obviously", "seamless", "robust", "powerful", "cutting-edge".
- Comments explain **why**, not **what**. The code says what.
- No emoji in source. No emoji in docs except in `PRD.md` user stories where it aids scanning.

---

## 7. Definition of done for a milestone

A milestone in `TASK.md` is done when:

1. Every checkbox under it is checked.
2. The gate command for that milestone passes.
3. The quota report (`quotaProbe`) shows the feature's steady-state footprint within its declared budget.
4. The relevant `TESTING.md` suites exist and pass.
5. `RE_ANALYSIS.md` and this file are updated if a locked decision changed.

---

## 8. Current state

- **Phase 1 complete** — `RE_ANALYSIS.md` (frozen reference analysis).
- **Phase 2 complete** — decisions D1–D6 locked above.
- **Phase 3 complete and approved** — the spec suite in this directory.
- **Phase 4 in progress** — M0 through M14 are implemented, except that M11 was reopened at M15 for the one control it never shipped (§9 of `PRD.md`: takeover on the device console). M15 (hardening and release) is the active milestone, and its remaining items need a device and a release build.
- **The app is installable and does something.** The console activity hosts the disclosure, the brain setup and the console; it starts and binds `:agent`; each goal carries its own brain configuration, pin and limits; the settings screen is behind the console's own header and its four device values cross `applyDeviceSettings` on every change and on every bind; the notification's Stop aborts the live run; the budget meters are read over `getBudget`; the glass indicator is driven from the facade through the bridge; and the loop names every step it chooses before it takes it (`RunProgress` in `:core:agent`), so the console's step counter, intent line and reason line move while a run works. Before this, the shipped APK showed a placeholder `Text("LUMEN")` and nothing could reach the runtime. **What that sentence describes is the console, not a finished run:** the surface policy refuses every action because the package allowlist it checks against is empty and nothing can fill it, so no step has ever been dispatched on a device — see the allowlist paragraph below, and `TASK.md` §M15.

**Do not write application code outside the current milestone in `TASK.md`.**

**Nothing is built on this workstation.** The operator's standing instruction is that no Gradle task is run here — the machine is too small for a build to be safe, and a build that thrashes it is worse than no build. Every command in §4 belongs to CI, and an agent that "just wants to check" has still spent the account's minutes and the machine's headroom. Verify a change by reading it, by the module's own contract in this file, and by the CI run after it lands.

What was measured here, once, before that rule was set: during the M15 ledger work the full JVM gate (`lint ktlintCheck detekt test dependencyGuard dependencyLedger`) ran green — 281 unit tests, no failures. Treat that as history, not as a workflow to repeat; the milestone status lines in `TASK.md` carry the run references.

What cannot run anywhere but on a device, by construction: `connectedDebugAndroidTest`, the golden-screenshot suite, `tools/quota-probe.sh`, the thermal and battery soak, and the release build's signing path. Those are CI's `device` job or the operator's handset, and a milestone that claims one of them without a device report attached is not done.

**M15 also found five claims that the code does not support, and the settings batch closed three of them.** Closed: the loop names each step it chooses before it takes it, so the counter, the intent line and the reason line move during a run; `SettingsScreen` is reachable from the console header and its controls have sinks; and the crash reporter is installed as `:agent`'s uncaught-exception handler, chained to the handler it replaces, with the flag that permits a write arriving from the user rather than from the build. Open in full: the thermal and battery governors are never called (§2 above), and the cost meter has no source. Open in half: the progress claim's graph half — the console still cannot draw the plan **graph**, because the state stream carries a position in the plan and a plan version but not the plan's shape, and the per-step result fields on the event payload (`stepId`, `riskClass`, `app`, `durationMs`) have no writer. And the settings screen left one promise it never made good: the switch used to be called *Live spectating*, and what actually reaches the runtime is the screen-text consent — the frame half has no producer at all (`FramePipeline` is never constructed, `:platform:capture` is used by no module, `publishFrame` has no caller), so the toggle is now named *Send the text on screen to the brain*, which is what it governs. Each of these is an item in `TASK.md` §M15. None of them is a quota or a safety breach in the direction that matters — each fails closed — but each is a promise in these documents that the build does not keep.

**CI is green end to end — shell and emulator — and getting there is the record.** The emulator job found five real faults, none of them a shell regression. Run `40118050282` passed the whole gate (`lint ktlintCheck detekt test dependencyGuard dependencyLedger :app:assembleDebug`), the web console, the specification check, and the `Installable APKs` job, which publishes `app-arm64-v8a-debug.apk`, `app-x86_64-debug.apk` and a universal APK. That run's `device` job executed for the first time and reported two faults that were in the tree long before the shell: an instrumented test class in `:platform:accessibility` named methods with spaces, which DEX only permits from version 040 (API 33) while this tree builds for API 26, and a glass shader test that drew a `RuntimeShader` into a bitmap canvas — the software pipeline, which refuses exactly that, on a handset as plainly as on an emulator. Both are fixed, and `TESTING.md` §1 now carries the rules they were written against. The same log gave away a third fault that produces no error at all: `:core:store:connectedDebugAndroidTest` reported `Starting 0 tests`, because a library module has no default `testInstrumentationRunner` and AGP adds no `<instrumentation>` element without one — so the store's seven device tests — five Room round trips, the plaintext probe and the schema check, the only exercise the encrypted store and the exported schema ever get — had never run anywhere, in any build. Both modules that hold instrumented tests now declare the runner, and run `40177522176` is that suite's first verdict: the store's seven tests **executed**, and five of them failed on `'01JBX0000000000000000000001' is not a valid run id` — the fixture plan in `RoomStateStoreTest` carried 27-character ULIDs, and `RunId` and `StepId` validate their string on construction. The literals are now the 26-character form the value classes accept, and every other identifier literal in the tree was scanned for the same defect. Six of seven then passed in run `40178634218`; the seventh, the plaintext probe, found something worse than bad test data. **The encrypted store had never opened on a device.** `net.zetetic:sqlcipher-android` registers its JNI methods when the native library is loaded and loads nothing itself, and nothing in this tree ever called `System.loadLibrary("sqlcipher")` — so `LumenDatabase.open`, the only path to the database that holds the observation store, died in `SQLiteConnection.nativeOpen` with an `UnsatisfiedLinkError`. Every claim about encryption at rest in `ARCH.md`, `PRD.md` and `TECHSTACK.md` was a claim about code that could not run, and it survived because the suite that would have proved it started zero tests for the entire history of the repository. `SqlCipher.ensureLoaded()` is now called by both open paths, there is no unencrypted fallback, and the probe is the test that says so. **Run `40179662588` is the first green `device` job in the repository's history**, and it is a verdict rather than a colour: `:core:store` started 7 tests and finished 7 with nothing failing — the five Room round trips, the schema check, and the plaintext probe, which opened a real SQLCipher database on the emulator and confirmed the marker was not readable in the file. Encryption at rest is now a measured property, not a specification. The same run put the other two suites' fixes beyond doubt: `:platform:accessibility` started five tests and skipped `I01`/`I02` as designed (no CI emulator has the service enabled), and `:app` started and finished its two glass tests. The static gate — `lint ktlintCheck detekt test dependencyGuard dependencyLedger :app:assembleDebug` — is green on the same run, after one detekt finding of its own: `DisclosureScreen` had grown past the 60-line cap on the way to the consent-copy change and its control section is now a composable of its own.

**Three more faults on the path to a first run were found by reading that path rather than building it**: the bind to `:agent` only happened on a resume, which the console never reaches when the user arrives by saving an endpoint, so the goal field was talking to no one; the stop and approval controls returned silently when there was no binder, where the screen now says the request did not leave and, for an approval, that the gate will time out denied; and the disclosure compared the enabled-services setting as text, which would have stranded a device that stores the qualified component form. All three are fixed in `:app`.

**Three more on the approval gate came from the same kind of read, after that batch landed.** An approval card reached the console only after its gate had closed, so the screen never showed a card and `I4`'s deny-by-default denied steps in silence — the record is now published when the gate opens, and a console binding mid-run is handed the live state and the still-open gates on registration, because a push nobody received is gone. A stop pressed while a run waits on a gate did nothing until the lease ran out, which made `I12`'s "within one action boundary" untrue at exactly the moment the user is most likely to press it; the abort now closes the run's open gates through `ApprovalBroker.expireOpen`, denied, never approved. And the disclosure was a wall — a user who left the service off was kept on the instructions page and *Not now* quit the app; it is now read once, and the console says what is missing with a `GroundingNotice` instead of hiding the app behind it. `ApprovalPayload` carries a `state` field to make this work: it is the wire difference between `approval.requested` and `approval.resolved`, and it is what lets a console drop the right card when the runtime says a gate closed.

**Two process-boundary defects were found and fixed while wiring the shell**, because both would have shown up as a broken app on a handset rather than as a CI failure: the agent bound to `LumenAccessibilityService` directly, a component guarded by the signature-level `BIND_ACCESSIBILITY_SERVICE`, which the platform answers with a `SecurityException` that killed `:agent` at service creation — it binds to `AccessibilityBridgeService`, the unguarded component that exists to hand out that binder; and the console refused to bind at all when notifications were blocked, which on API 34 has no recovery path once the user declines, hiding a notification being no reason to lose the run.

**The settings batch came from asking what each control is connected to, and found five disconnects.** `SettingsScreen` was named by nothing, so it is now behind a *Settings* control in the console header, with `MainActivity.ConsoleHost` holding the two views of one connection rather than a navigation graph with one real branch. The four values on it had no path to the device, and each has one: the indicator mode reaches `RunState.indicatorMode` and is relit for the surface the user is looking at *and* remembered for the next run; the idle window reaches the gate through a new `IdleWindow` holder, because the window was a constructor argument of a gate built with the runtime, and a setting that cannot outlive the object it configures is not a setting; crash reporting reaches `CrashReporter.enabled`; and the brain pin reaches a run at all — `AgentConnection` never filled `GoalPayload.brainPin`, so every run was planned as `AUTO` whatever the user chose, which made `D2`'s pin a display field rather than a control. The values cross a new AIDL call, `applyDeviceSettings` (`API_SPEC.md` §2), and are re-sent on **every bind** instead of written to a file `:agent` could read: the processes share no cache, a restarted process starts from defaults, and a console showing one value while the runtime acts on another is the failure this batch exists to remove. Three things fell out of the same read: the reporter is installed on `:agent` alone, because a second writer would hold a second `StorageQuota` and `I10` cannot be a sum of two under-counters — which is why the factory now holds one quota and one workspace for the process rather than a new pair per call; `IdleWindow` refuses anything under five seconds, because a device setting is input and a window of zero would let an action land a millisecond after the user's own touch (`I3`); and the switch called *Live spectating* is now called *Send the text on screen to the brain*, because the frame half of it has no producer and the text half is the half the runtime honours. **No verdict on any of it yet** — nothing is built here — and the eight tests it adds (three on the window's holder, three on the indicator word, two on the payload) are claims until a CI run says otherwise.

**One more claim, found while writing that paragraph and not fixed by it:** `TECHSTACK.md` §6 names DataStore Preferences for the settings keys, `AppSettings` uses `SharedPreferences`, and `androidx.datastore:datastore-preferences` is declared by `:core:store`, which imports it nowhere. Where the keys live is an operator's decision — `TASK.md` §M15 carries the three ways it could go.

**Reading the settings batch's own path found the defect that outranks all of it: no run can complete a step.** `AgentEnvironment.perform` asks `SurfaceSelection` which surface an action may use, and `SurfaceSelection.denialFor` refuses any package that is not on the request's allowlist — the `D1` and §4.4 check, on the dispatch path, before the idle gate, exactly as specified. The set it checks against is empty and nothing can fill it: `AgentEnvironment`'s `allowlist` is a supplier defaulting to `{ emptySet() }`, `AgentRuntimeFactory.environment()` builds the environment without passing one, and no console control names a package, so every action returns *"the package … is not on the allowlist"*. This is `I3`/`I4` behaviour in the safe direction — the agent has never touched an app the user did not allow, because it has never touched anything — and `§8`'s "the app is installable and does something" says what the console does, not what a run achieves. The cheap half is internal: `GoalConstraints.allowedPackages` already exists in `:core:model`, already crosses `GoalPayload`, and already lands in `startGoal`, where nothing reads it; threading it into `SurfaceRequest` adds no dependency, permission or capability. The half that is a Phase 4 decision is how a package gets named — free text on the goal composer needs nothing, a picker over installed apps needs `QUERY_ALL_PACKAGES` — so `TASK.md` §M15 carries it as the item blocking a first successful run and `PRD.md` §8 states it as an open product question rather than an answer.
