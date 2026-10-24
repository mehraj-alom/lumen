# PRD.md — LUMEN (Mobile Operator Agent)

**Status:** Draft for review · **Codename:** LUMEN (placeholder) · **Spec version:** 1.0

---

## 1. Problem

A person who owns an Android phone has dozens of small, tedious, multi-app chores: research a product across the web, open three apps, fill in a form, compare, send a message, log the result somewhere. Each chore is 15–100+ taps across 2–5 apps, and every one of them is currently done by hand while the phone is the only tool you have.

Existing automation on Android falls into two failure modes:

- **Scripted macros** (Tasker, AutoInput) replay a fixed path. They break the moment the UI shifts, and they cannot decide anything.
- **Cloud phone farms / remote-control apps** require you to hand your account and your screen to someone else's infrastructure, and they charge per minute.

Neither is a *digital twin* — something that holds an intent, plans a path, notices when reality diverged, asks before the irreversible part, and shows you what it is doing while it does it.

## 2. Product definition

**LUMEN is an on-device Android agent that turns one high-level intent into a long-horizon, multi-app workflow, with a human confirmation gate in front of every irreversible step, and a live glass console you can watch and interrupt.**

Three things make it different from a macro:

1. **It plans.** The goal is decomposed into a step graph and revised mid-flight when the world disagrees.
2. **It is bounded.** Hard ceilings on RAM, storage, steps, wall-clock, and money. A run cannot run away.
3. **It yields.** Irreversible actions stop and wait for you. You can take over at any point, instantly, from the device or the web console.

### 2.1 Non-goals

- Not a macro recorder or replay engine.
- Not a hosted device farm; the agent runs on your phone.
- Not a general-purpose autonomous agent that writes and executes its own code.
- Not a replacement for the OS accessibility policy. LUMEN is subject to the same Play Store rules as any accessibility tool: it is a user-initiated automation tool, disclosed to the user, and visible while it runs.

### 2.2 Topology variants and their cautions

`D5` locks the recommended topology, but the user may switch to a variant at any time. The choice is a **product decision with real consequences**, so each variant ships with its cautions attached. The console exposes the switch; switching takes effect on the next run, not mid-run.

**Variant A — On-device agent runtime + separate web console (RECOMMENDED, the locked default)**

The agent runtime ships as the Android app. The web console is a separate static application that shows the live spectating view and issues high-level intents.

| | |
|---|---|
| **Caution (medium)** | The console is a second deployment surface: it needs hosting, a TLS certificate, an account system, and a WebSocket endpoint. It is a real ongoing cost, and the user must trust a third party with the intent stream. |
| **Caution (medium)** | The live frame path moves screen content off the device. It is opt-in and separately revocable (`ContentEgressGuard`), but the *existence* of the path is a privacy consideration for anyone who installs the app. |
| **Caution (low)** | Two code bases, two release trains. The web console is a TypeScript app; the agent is Kotlin. |
| **Why it is the default** | It is the only variant that gives a usable spectating experience. A phone screen cannot show a live frame of itself while also showing the console. |

**Variant B — Fully on-device, console inside the app**

Everything, including the live view, lives in the Android app.

| | |
|---|---|
| **Caution (medium)** | The live spectating view is degraded. You watch the agent work on the phone screen, which the agent may be driving. There is no second surface to watch from. |
| **Caution (medium)** | A 100-step run cannot be supervised from a desk. The user must be holding the phone. |
| **Caution (low)** | Spectating a run from a computer is not possible at all. |
| **Why it might be chosen** | No network dependency at all. The lowest privacy surface. The strongest option for a device that must never talk to a server. |

**Variant C — On-device executor + cloud control plane**

The executor and the actuator live on the device; the planner, the run history, and the console live in the user's cloud.

| | |
|---|---|
| **Caution (high)** | The run state lives off-device, which is the exact gap this product exists to close (`RE_ANALYSIS.md` §7, G1 and G2). A 100-step run is not resumable without the cloud. |
| **Caution (high)** | The cloud can see the intent, and — unless spectating is off — the screen. This is the reference app's topology, and it is why the reference cannot work offline. |
| **Caution (medium)** | Every step is a network round-trip. On a 100-step run, the latency is the dominant cost. |
| **Why it might be chosen** | The strongest possible reasoning quality, if a large model is worth the dependency. |

**Recommendation:** Variant A. It is the locked default in `D5` and the only one that satisfies the live-spectating requirement properly.

**The switch is user-facing, and the cautions are shown at the point of choice.** Choosing a variant is never a one-tap decision, and the console states plainly what that variant gives up.

---

## 3. Personas

| Persona | Need | Primary surface |
|---|---|---|
| **Power user / founder** | Run the 40-minute research-and-log workflow before a standup | Web console (specifying the goal), device (approving) |
| **Analyst on a train** | Same goal, no laptop | On-device console |
| **Privacy-conscious user** | Screen content must not leave the device | Local brain pin + spectating disabled |
| **Developer of LUMEN itself** | Reproduce a failure from a trace | `traces/` JSONL + `workspace/` |

---

## 4. Core user stories

### 4.1 Long-horizon execution

- **US-1** — As a user, I give one sentence ("find the cheapest flight to Lisbon next Tuesday under €400 and put it in my notes app"), and the agent produces a 60+ step plan and executes it across the browser, a travel app, and a notes app.
- **US-2** — As a user, I see the plan before it starts, can delete steps, reorder them, and pin one step as "ask me first".
- **US-3** — As a user, I can stop a run at any step boundary, and the partial result (everything completed so far) is still saved and readable.
- **US-4** — As a user, I get told *why* each step happened, in one sentence, not a stack trace.
- **US-5** — As a user, when the app under automation changes its layout mid-run, the agent notices the step no longer matches and re-plans instead of tapping blindly.

### 4.2 Human in the loop

- **US-6** — As a user, I am asked before anything that spends money, publishes, deletes, sends to a person outside my contacts, or grants a permission — even if the agent is confident.
- **US-7** — As a user, the ask shows me exactly what will happen, on what app, with the concrete content (the message text, the amount, the item).
- **US-8** — As a user, I can answer an ask with free text ("actually, use the second option") and the agent continues.
- **US-9** — As a user, if I do nothing, the ask times out and the run pauses safely — never continues on an assumed yes.

### 4.3 Non-intrusiveness

- **US-10** — As a user who is scrolling Instagram, the agent does not draw on my screen. It either waits or works on a private virtual display.
- **US-11** — As a user, I can see at a glance — from the edge of the screen, without opening anything — that the agent is currently working.
- **US-12** — As a user, I can turn that indicator off, or switch it from an edge glow to a small dot.

### 4.4 Live spectating and takeover

- **US-13** — As a user, I open a web console and see the agent's current screen, its reasoning trace, its step counter, and its remaining budget, updating live.
- **US-14** — As a user, I can take over from the web console: my touches win, the agent pauses, and it asks me what to do next.
- **US-15** — As a user, I can see exactly which frames left my phone, and can turn spectating off entirely to guarantee no screen content leaves the device.

### 4.5 Resource governance

- **US-16** — As a user, the agent's total RAM never exceeds 2 GB, and I can see the breakdown in the console.
- **US-17** — As a user, the agent's storage never exceeds 20 GB, and old frames and traces are evicted automatically with a visible policy.
- **US-18** — As a user, on a hot or low-battery device, the agent slows down, suspends local inference, or pauses — and tells me why.

---

## 5. The 100-step workflow specification

This is the reference workload the agent must survive. It is the acceptance test for "long-horizon".

**Goal:** *"Find the cheapest direct flight to Lisbon departing next Tuesday, under €400 each way, and add it to my travel notes app."*

| Phase | Steps | Apps | What happens | Risk class |
|---|---|---|---|---|
| A. Orient | 1–6 | Browser, Settings | Confirm date, currency, account identity; read device clock and locale | None |
| B. Research | 7–48 | Browser, search, 2 flight sites | 3–5 candidate queries, open 2 result pages, scrape prices, dates, baggage rules, filter by price/directness | Read-only |
| C. Compare | 49–62 | Browser | Cross-check the top candidate against a second site, reconcile currency and fees | Read-only |
| D. Book | 63–78 | Travel app | Select flight, passenger, seat, payment method | **Payment — HITL** |
| E. Publish | 79–88 | Travel app, notes app | Write the itinerary, paste into notes, format | **Publish — HITL** |
| F. Notify | 89–94 | Messaging app | Send the itinerary to a saved contact | **External message — HITL** |
| G. Verify | 95–100 | Notes app, browser | Re-read the note, confirm the booking reference, close cleanly | Read-only |

**Requirements derived from this workload:**

| ID | Requirement |
|---|---|
| W1 | The plan must survive process death. Killing the app at step 44 and relaunching must resume at step 45 with identical state. |
| W2 | Step 63–94 contains **three separate** HITL gates. One approval never covers two gates. |
| W3 | Each step records a pre-state and a post-state; a full trace of 100 steps must reconstruct the run. |
| W4 | The agent must survive at least 5 app switches and 3 crashes mid-step. |
| W5 | The agent must re-plan at least twice (a fare changes, a seat is unavailable) without restarting the goal. |
| W6 | No screen content leaves the device during phases A–C unless spectating is on. |
| W7 | Total wall-clock ≤ 45 min at default budget; the user may raise it to 4 h. |
| W8 | Peak RAM ≤ 2 GB with the local model resident. |
| W9 | The indicator is visible for the whole of phases B–F, and only those phases. |
| W10 | Aborting at any step leaves the device in a state the user can recognise and undo. |

---

## 6. HITL gates — the full list

A step **must** stop and ask when it matches any class below. Classification is deterministic and runs on-device *before* the action dispatches; a brain-model opinion never overrides it.

| # | Class | Examples | Default | Timeout |
|---|---|---|---|---|
| H1 | **Payment** | Any card, wallet, transfer, subscription, purchase, tip, deposit | Ask | 5 min |
| H2 | **Publish / send** | Post, comment, publish, upload, share, email, message to a non-self contact | Ask | 5 min |
| H3 | **Delete / destroy** | Delete, uninstall, wipe, revoke, cancel subscription, close account | Ask | 5 min |
| H4 | **Credential** | Type a password, OTP, PIN, card number, 2FA code | Ask + blur | 2 min |
| H5 | **Permission grant** | Allow notifications, contacts, camera, accessibility, overlay, install | Ask | 2 min |
| H6 | **Communication** | Call, ring, join a meeting, send SMS, answer a call | Ask | 2 min |
| H7 | **Account change** | Change email, password, phone, recovery, 2FA settings, linked accounts | Ask | 5 min |
| H8 | **Legal acceptance** | Terms, ToS, consent, arbitration, recurring charge | Ask | 5 min |
| H9 | **Irreversible external state** | Sending mail, posting a review, rating, donating, joining a paid group | Ask | 5 min |
| H10 | **User-pinned step** | Any step the user explicitly marked "ask me first" | Ask | User-set |

**Rules:**

- Default is **deny**. A timeout denies. An unreachable surface denies. There is no "auto-approve" toggle for H1–H8. Only H10 (user-pinned) and an explicit, per-run, expiring grant may pre-approve, and a grant is bound to one action class and one run.
- An ask is **staged, not blocking**. The executor thread is released; the step waits on a durable `PendingApproval` row.
- Two independent surfaces carry every ask: an in-context glass card over the current app, and a notification with actions. Either can answer.
- The ask must show: the app, the concrete content, the detected class, the irreversible consequence, and the exact action that will be taken.
- Free-text answers are allowed and are fed back to the planner as new information, not as a bypass.

---

## 7. The active-status indicator

Three modes, user-selectable, persisted across runs, overridable per session.

### Mode A — Edge glow

A luminous band along the screen perimeter. Implementation: AGSL `RuntimeShader` on a full-screen `Compose` layer, plus a `RenderEffect` blur-behind so the band appears to refract the content beneath it.

- Colour is **state-driven**: `idle` (dim slate), `working` (lumen cyan), `awaitingApproval` (amber), `blocked` (crimson), `error` (crimson, fast pulse), `aborting` (amber, fast pulse).
- Motion is a slow travelling highlight; it accelerates to ~2 Hz in `awaitingApproval`.
- The band is 6–10 dp thick with a 1 dp specular inner rim and a soft outer falloff of ~24 dp.
- It renders **above** app content and **below** the system status bar.

### Mode B — Status dot

A single 10 dp pulsing dot at the top centre, 24 dp below the status bar. Same colour semantics, same state machine, one third of the pixel cost. Tapping it opens the agent console.

### Mode C — Off

Nothing is drawn. The agent still shows a persistent notification while a run is active — that is a platform requirement for foreground services and is not the indicator, and it is not configurable away.

### Indicator rules

- The indicator reflects the **agent runtime**, not the app. It is on while a run is active, paused, or waiting for approval; off when idle.
- It is `runId`-idempotent: a stale UI cannot clear a newer run's indicator, and a run cannot clear another's.
- It is **never** the only signal that the agent is active. The notification and the console always agree.
- The user can disable the indicator without disabling the agent.

---

## 8. Non-intrusive execution

| Mechanism | Requirement |
|---|---|
| **Idle gate** | Live-screen actions are permitted only when the device is idle: screen off, or on and untouched for a configurable window (default 60 s), and no touch events observed. Any touch resets the idle timer and the agent pauses at the next action boundary. |
| **Virtual display** | When the target app can render on a secondary display, the agent moves it to a private `VirtualDisplay` and drives that surface. The live screen is untouched. |
| **Allowlist** | The agent may only act on apps the user has explicitly allowed. Default allowlist is empty; `com.android.settings` and the user's target apps are added per run. |
| **Foreground-app guard** | Before any live-surface gesture, the foreground package is re-checked against the allowlist. A mismatch aborts the step, not the run. |
| **Takeover** | Any user touch on a live-surface run pauses the agent within one action boundary (≤ 1 s). |

---

## 9. Live spectating

- The console shows: a live frame stream (downscaled, ~2 fps), the current step, the plan graph with the active node highlighted, the reasoning trace (the planner's own commentary, not raw prompts), the budget meters (RAM, storage, steps, time, cost), and the HITL queue.
- **Instant takeover:** a "Take over" button pauses the agent and switches the device into manual mode. The agent does not resume until the user explicitly releases it.
  - **Implementation status:** built for the web console, not for the device console. `AgentRuntimeFacade.setTakeover` aborts the run and holds it in `PAUSED` until `setTakeover(false)`, and `SpectateView.tsx` exposes the button; the Compose console has Approve, Deny and Start and no takeover control (`LumenConsole.kt` records this, `TASK.md` M11 keeps the item open). What the device does give is §8's passive takeover: a touch reaches `TouchObserver` through the accessibility event clock, the idle gate then refuses the live surface, and the agent stalls at the next action boundary — but it resumes on its own once the screen is idle again, which is not the semantics asked for here.
- **Egress control:** spectating is off by default. When off, no frame, screenshot, node text, or app name leaves the device — enforced by `ContentEgressGuard`, not by UI convention.
- **Granularity:** spectating can be set to *frames only* (see what the agent sees) or *frames + reasoning* (see what it thinks).

---

## 10. Resource budget as a product feature

| Budget | Limit | User-visible |
|---|---|---|
| RAM | 2,048 MiB total | Live meter + breakdown by consumer; local model unload is automatic, not a crash |
| Storage | 20,000 MiB in sandbox | Live meter; eviction is transparent but explained; `workspace/` is user-visible and never auto-deleted |
| Steps | 150 soft / 400 hard | Counter in the console; soft limit prompts the user to extend or stop |
| Wall-clock | 45 min default / 4 h hard | Countdown; hard stop is a clean, resumable pause |
| Cost (cloud calls) | User-set ceiling, default $0 | Counter; the agent refuses to exceed it and pauses instead |

---

## 11. Non-functional requirements

| ID | Requirement |
|---|---|
| N1 | The agent is fully usable **offline** when the brain is pinned local and the goal needs no network. |
| N2 | The first screen of the console renders in < 300 ms after process start. |
| N3 | A 100-step run's trace replays deterministically: same seed, same device state, same plan (timestamps aside). |
| N4 | The app never crashes the target app. If the target dies, the step fails and the run replans. |
| N5 | The agent degrades rather than dies: no local model → cloud; no cloud → local; neither → the run pauses with a clear reason. |
| N6 | All state survives process death. There is no in-memory-only state in the run path. |
| N7 | The console is readable in direct sunlight (contrast ≥ 4.5:1 for body text on glass). |
| N8 | Every network call the agent makes is attributable to a run id, a step id, and a reason. |

---

## 12. Resolved defaults

These were open at review time. Each now has a decision, so the implementation
is unblocked. Each is a single constant in the configuration and can be changed
without an ADR, except where noted.

| # | Question | Decision | Changeable |
|---|---|---|---|
| 1 | Default wall-clock per run | **45 minutes**, 4 hours hard ceiling. A goal whose plan is under 20 steps gets a 20-minute cap | Yes — per run, in the console |
| 2 | Cloud cost ceiling | **A hard dollar gate**, default **$0.00** per run. The agent refuses to exceed it and pauses with `quota_exceeded` rather than degrading silently. A per-run token budget is the alternative, not an addition | Yes — per run, in the console |
| 3 | Default brain | **`AUTO`**: local if a model is installed *and* fits the RAM budget, otherwise cloud. With no model installed the agent is cloud-only, and the console states this on first run | The pin is `AUTO`/`LOCAL`/`CLOUD` and is settable per run |
| 4 | Allowlist default | **Empty.** Every app must be added explicitly, per run or as a standing grant. `QUERY_ALL_PACKAGES` resolves a package name to a launch intent; the result is never persisted beyond the run | Yes — the standing grant list is editable |
| 5 | Notes app target | **App-agnostic.** The 100-step workflow uses a capability-detection step ("an app that can store text") rather than naming a specific app, so the specification survives a different device. A named default is a console preference, not a product rule | Yes — a console preference |
| 6 | Two-person approval for payments | **Single approver.** A second approver is a future feature, and the specification reserves the shape: an approval carries an `approvers` list and the policy engine checks it. A second device is not built in v1 | Additive — no ADR required |

**Consequence worth stating plainly.** Decisions 2 and 4 together mean the
default install does nothing the user did not explicitly authorise: no cloud
spend, no app access, and no screen content leaving the device.
