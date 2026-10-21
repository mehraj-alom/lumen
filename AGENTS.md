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
├── android/               # Gradle project
│   ├── app/                # console UI (Compose), process: :ui
│   ├── agent/              # runtime, planner/actor/critic, process: :agent
│   ├── core/               # pure-Kotlin: model, brain, policy, quota, sandbox, store
│   ├── platform/           # accessibility, capture, virtualdisplay, idle
│   └── console-web/        # web spectating console (D5)
├── docs/adr/              # Architecture Decision Records
└── tools/                 # dev scripts (bench, quota probe, tree dumper)
```

**Module boundary rule:** `core/*` is **pure Kotlin/JVM** — no `android.*` imports, no framework types in public APIs. `platform/*` is the only place Android APIs live. This is what makes the loop testable on the JVM.

**One named exception (ADR 0002):** `:core:store` is an Android library, because Room is an Android-only artifact. The persistence *port* (`StateStore`) and every DTO live in `:core:model`, which stays pure, so `:core:agent` — the loop — remains a plain JVM module with a fake store, and `:core:agent:test` needs no Robolectric. `:core:store` may depend on `androidx.room`, `androidx.datastore` and `androidx.sqlite` and nothing else outside `core/`.

---

## 4. Build, test, lint commands

```bash
# Build
./gradlew :app:assembleDebug          # console + agent app
./gradlew :agent:assembleRelease      # agent runtime
./gradlew :console-web:build          # web spectating console

# Test
./gradlew test                        # all JVM unit tests
./gradlew :core:test :core:testDebug  # core module unit tests
./gradlew connectedAndroidTest        # instrumentation (device required)
./gradlew :core:quotaTest             # quota watchdog + sandbox validation
./gradlew :core:loopRecoveryTest      # multi-step loop recovery suite

# Static analysis
./gradlew lint                        # Android Lint, errors fail the build
./gradlew ktlintCheck detekt          # Kotlin style + complexity
./gradlew :core:dependencyGuard       # fails on a banned dependency

# Benchmarks
./gradlew :core:jmh :core:benchmark   # planner latency, tree parse cost
./gradlew :core:quotaProbe            # on-device RAM/storage report

# Full gate (what CI runs)
./gradlew lint ktlintCheck detekt test :core:dependencyGuard
```

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
- **Phase 4 in progress** — M0 (repository and toolchain bootstrap) is the active milestone.

**Do not write application code outside the current milestone in `TASK.md`.**
