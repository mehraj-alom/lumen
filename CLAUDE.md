# CLAUDE.md — LUMEN (Mobile Operator Agent)

**Read `AGENTS.md` first. It is the binding contract for this repository.** This file is a pointer plus a compressed copy of the rules that are easiest to break.

```
@AGENTS.md
```

If you only read one file, read `AGENTS.md`. If you read two, read `AGENTS.md` and `ARCH.md`.

---

## The 12 invariants in one line each

1. RAM ≤ 2 GB for the whole agent runtime, memory graph, and workers.
2. Storage ≤ 20 GB inside the app-scoped sandbox; nothing outside without permission.
3. Never hijack an active touch session — idle gate + virtual display.
4. HITL is mandatory for every irreversible or sensitive action class. No bypass.
5. The agent never executes code it wrote on a previous run.
6. Every action is observable and reversible-logged; pre-state recorded before dispatch.
7. No proprietary code, assets, or text copied from the reference APK. Patterns only.
8. Determinism where possible: same goal + same device state ⇒ same plan, modulo timestamps.
9. Never act on the user's behalf without an auditable `Reason`.
10. Quotas are enforced by construction, not monitored after the fact.
11. No network call carries screen content off-device unless spectating is enabled.
12. The user can always take over instantly — abortable within one action boundary.

## Locked decisions (Phase 2)

| ID | Decision |
|---|---|
| D1 | Isolation: hybrid — live screen only behind idle gate + allowlist, opportunistic `VirtualDisplay` offload |
| D2 | Brain: adaptive + user-selectable — local if a model fits the RAM budget, else cloud; user pin always wins |
| D3 | UI: Kotlin + Jetpack Compose + AGSL `RuntimeShader` + `RenderEffect` |
| D4 | Storage: Room hot state + rotating tiered file logs, LRU eviction, 20 GB cap |
| D5 | Topology: on-device runtime + separate web console (user may switch to fully-on-device) |
| D6 | Adopted reference patterns: see `RE_ANALYSIS.md` §6 and `ARCH.md` §4 |

## Before you write code

- Check `TASK.md` — work only inside the current milestone's unchecked items. If your task is not there, ask.
- A new **dependency**, **permission**, **platform capability**, or **quota change** requires the user's explicit approval first. Do not add it and mention it afterwards.
- All file I/O goes through `SandboxPath`. All screen access through `GroundingPort`. All inference through `BrainPort`.
- `core/*` is pure Kotlin/JVM — no `android.*` imports, no framework types in public APIs. `platform/*` is the only place Android APIs live.

## Before you ship

```
./gradlew lint ktlintCheck detekt test dependencyGuard dependencyLedger
```

That is the gate, and **it is CI's command, not this workstation's** — `AGENTS.md` §8: nothing is built here, the machine is too small to build on safely and the operator has ruled out local builds. Read the code against the contract instead, and take the verdict from the CI run after the change lands.

Plus, from `AGENTS.md` §6.3: a unit test in the owning module, a sandbox-path test for every new I/O path, a cancellation test for every new loop, and a tested Room migration for every new state field. State the quota impact of the change.

## Prose rules

Plain, specific, no marketing adjectives. No "simply", "just", "obviously", "seamless", "robust", "powerful". Comments explain **why**, not **what**. No emoji outside `PRD.md` user stories.

## Current state

Phase 3 is complete and approved; Phase 4 is on M15 (hardening and release). The per-milestone **Status** lines in `TASK.md` are the record of what was actually verified, and M15's says what is blocking it now: CI's jobs are not starting, because the account's Actions spending limit needs attention in Billing & plans. Until a run can start, no change here has a gate verdict.
