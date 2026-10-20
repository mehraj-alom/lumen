# RE_ANALYSIS.md — Clean-Room Architectural Analysis of the Reference APK

**Target artifact:** `melaya-phone.apk` (the only APK present in the project workspace)
**Package:** `org.melaya.phone` · **Version:** 1.1.0 (200) · **minSdk 24 / targetSdk 35** · **Size:** 65,530,264 bytes
**SHA-256:** `274168d81cd9a7fab2e372e80a4e56d2b49935e2388c41d7b26cac2cc03cb43d`

**Tooling (REA):** REA CLI 6.7.0 driving JADX headless MCP 0.7.1 (engine artifact SHA-256 `6e5eacf5…573ff75`, matching REA's pinned metadata) + Apktool 2.12.1 for resource decoding. All findings below are static observations of the shipped artifact. No code, asset, or text was copied into this project — only architectural patterns are recorded.

> **Note on tool surface.** The REA MCP server is registered with the agent host and healthy (`doctor --client opencode` → `healthy: true`, 207 tools, jadx provider `available: true`). This analysis session executed the same REA engine through its CLI surface, which returns the identical Evidence records the MCP tools emit. Restarting the host binds the MCP tools directly.

---

## 0. Executive Summary — What the Reference Actually Is

The reference is **not** an on-device AI agent. It is a **thin, stateless Android executor** that receives individual UI-primitive commands from a remote backend and applies them to the device, wrapped in a Capacitor/Ionic operator console.

The single most important architectural fact for our product:

> **All cognition lives off-device. The APK is a remote-controlled actuator with no planner, no memory of goals, and no VLM. Every decision — what to tap, what to approve, when to act — arrives as a one-shot job from a server.**

This inverts our product's premise. Our agent must be a **local, long-horizon planner** (Planner–Actor–Critic) that owns goal state, budgets, and HITL policy on-device, with the cloud reduced to optional high-reasoning calls. The reference is a useful catalogue of *actuator mechanics* and *HITL mechanics*, and a useful counter-example for everything planning-related.

| Dimension | Reference APK | Implication for our build |
|---|---|---|
| Planning location | Remote server (job objects) | We must own the planner locally |
| Grounding | Accessibility tree only — zero VLM/OCR | Tree-first is sufficient and cheap; VLM is a fallback only |
| Memory | SharedPreferences (token, allowlist, working label) | We need a real step/graph store with quota accounting |
| Orchestration | HTTP long-poll, one job per request | We need a resumable local step machine |
| HITL | Notification + a11y-overlay approval cards | Reuse the pattern, but make it first-class and stateful |
| Indicator | Canvas comet-trail border + aura overlay, in-app | Our glassmorphism indicator is a net-new design axis |
| Isolation | None (drives the live foreground app) | We must add idle-gating + virtual-surface isolation |

---

## 1. Manifest & Permissions

### 1.1 Component inventory (application-owned)

| Component | Type | Exported | Notes |
|---|---|---|---|
| `MainActivity` | Activity | ✅ (LAUNCHER) | Capacitor bridge host; deep-link + pairing intent handling |
| `WakeActivity` | Activity | ❌ | Screen wake / keyguard dismissal path |
| `RequestCastActivity` | Activity | ❌ | MediaProjection consent UI |
| `MelayaAccessibilityService` | Service | ❌ | `BIND_ACCESSIBILITY_SERVICE` — the actuator |
| `ScreenCaptureService` | Service | ❌ | `foregroundServiceType="mediaProjection"` |
| `PollService` | Service | ❌ | `foregroundServiceType="specialUse"` — the transport loop |
| `MelayaMessagingService` | Service | ❌ | Firebase Cloud Messaging wake channel |
| `ApprovalActionReceiver` | Receiver | ❌ | Notification-action HITL approval |
| `AskReplyReceiver` | Receiver | ❌ | Notification-reply HITL ask |
| `BootReceiver` | Receiver | ❌ | `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` restart |

### 1.2 Permission posture

Requested permissions, grouped by capability intent:

- **Screen reading & action:** `BIND_ACCESSIBILITY_SERVICE`
- **Screen capture:** `FOREGROUND_SERVICE_MEDIA_PROJECTION` (no `RECORD_AUDIO` use for capture; `RECORD_AUDIO` is present separately for voice)
- **Long-running execution:** `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- **User attention:** `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, `DISABLE_KEYGUARD`
- **Discovery & telephony:** `QUERY_ALL_PACKAGES`, `INTERNET`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`
- **Service binding:** `DUMP`, `BIND_JOB_SERVICE`

**Critical negative finding: `SYSTEM_ALERT_WINDOW` is NOT requested.** The reference injects its overlays from inside the `AccessibilityService` itself, which can add windows with the accessibility overlay type without the overlay permission. This is the mechanism behind its working/approval/ask overlays. Our product must choose deliberately between this route and a true `SYSTEM_ALERT_WINDOW` overlay (see Phase 2 Q1) — the reference proves the accessibility route works without a scary permission, but it also means the overlays **die with the accessibility service** and cannot outlive it.

### 1.3 Accessibility service configuration

From the decoded `res/xml/accessibility_service_config.xml`:

| Attribute | Value | Architectural meaning |
|---|---|---|
| `accessibilityEventTypes` | `typeAllMask` | Observe everything; the executor filters |
| `accessibilityFeedbackType` | `feedbackGeneric` | No spoken/haptic feedback to the user during automation |
| `notificationTimeout` | `40` | 40 ms debounce — the event storm is throttled at source |
| `accessibilityFlags` | `flagDefault \| flagIncludeNotImportantViews \| flagReportViewIds \| flagRetrieveInteractiveWindows` | Reads view IDs, non-important views, and other apps' windows |
| `canRetrieveWindowContent` | `true` | Full node-tree reads |
| `canPerformGestures` | `true` | Gesture injection (tap/swipe/long-press paths) |
| `canTakeScreenshot` | `true` | Platform screenshot path — a MediaProjection alternative |

`canTakeScreenshot="true"` is significant: the accessibility API can capture the screen on API 30+ **without** MediaProjection consent, which removes the recurring consent dialog from the spectating path.

### 1.4 UI technology posture

No Jetpack Compose, no Flutter, no React Native, no JNI/native libraries, no ONNX/MLKit/MediaPipe/Tesseract. The UI is **classic Android Views + custom `Canvas` drawing**, and the app shell is a **Capacitor (Ionic) WebView** hybrid: `capacitor.config.json` declares `appId: org.melaya.phone`, `webDir: public`, `androidScheme: https`, `hostname: app.melaya.org`, `webContentsDebuggingEnabled: false`, `loggingBehavior: none`.

---

## 2. Screen Grounding & Action Loop

### 2.1 Grounding is 100% accessibility-tree — no VLM, no OCR

Across all **4,985 classes** there is **no** ONNX runtime, no MLKit, no MediaPipe, no Tesseract, no OCR, no object-detection or classification model, and no native/JNI code. Every grounding decision is made over `AccessibilityNodeInfo`.

**Observation primitive** — the executor reads the screen into a JSON tree with a stable, serializable shape:

```
{ "app": "<package>", "nodes": [ { text, contentDescription, id, class, bounds, clickable, editable, ... } ] }
```

Node enrichment observed in the inventory: visible-text extraction, per-node signature/hash for change detection, clickable-ancestor resolution, subtree collection with a bounded node count, and a coordinate→node hit-test (`nodeAt(x, y)`).

### 2.2 The action vocabulary

The actuator exposes a fixed, closed set of primitives (dispatched by string name):

| Family | Primitives |
|---|---|
| Pointer | `tap`, `double_tap`, `long_press`, `swipe`, `scroll`, `dragHold` (path-based, eased) |
| Targeted tap | `tapById`, `clickText`, `clickById`, `clickTextInComposer`, `tapComposerSendEdge` |
| Text | `inputText`, `pasteText`, `pressEnter` |
| Navigation | `openApp`, `openUrl`, `listApps`, `returnToMelaya` |
| Observation | `screenTree`, `settledTree`, `takeScreenshotAction` |
| Composition | `execute` (single), `executeBatch` / `batch` (multi-step) |

Coordinates are sent in **relative space** and resolved to pixels on-device (`resolveX` / `resolveY`), so the planner never needs to know the device resolution.

Gesture synthesis is path-based for drags: a drag is converted into an interpolated point list with an easing function, then replayed as a `GestureDescription` stroke — not a naive start→end fling.

### 2.3 The single dispatch funnel

Every action funnels through one method: `execute(action, payload) → JSONObject`.

Observed contract:

1. Stamp `lastCommandAt` (used by the dead-man/heartbeat logic).
2. `"batch"` is routed to `executeBatch`.
3. Otherwise the action is posted to the **main thread** via a `Handler`, and the calling thread blocks on a `CountDownLatch` with a **15-second timeout**.
4. Timeout → structured error `device_timeout`; no result → `no_result`; success → `{ok: true, ...}`.
5. **Auto-observation:** for actions in an `observeAfterActions` set (scroll, swipe, and friends), the executor re-reads a settled tree after the action and merges `app`, `nodes`, and `overlays` into the result, marking it `observed: true`.

This is the crucial pattern: **action and observation are one atomic round-trip**. The remote planner never has to issue a separate "now screenshot me" call, and can never act on a stale screen.

### 2.4 The settle algorithm (the real engineering content)

Both `settledTree` and `waitUiQuiet` implement a **quiescence detector** over accessibility events rather than a fixed sleep:

- The service continuously tracks `lastUiEventAt` and `lastScrollEventAt` (separate clocks for scroll vs. non-scroll activity).
- A **25 ms polling loop** checks two exit conditions:
  - *Quiet* — no event for **160 ms** after the last observed change, or **90 ms** with no change at all.
  - *Stable* — the node count of successive trees differs by **≤ 2**, sampled every ≥ 250 ms, with a ≥ 400 ms minimum observation window.
- Hard caps: **1,500 ms** while scrolling, **2,000 ms** otherwise.
- Fallback: if the cached tree is older than 150 ms, re-read a fresh one rather than returning a stale snapshot.

**Architectural takeaway:** screen stability is a first-class, event-driven, bounded-wait primitive. Fixed `sleep()` calls are the failure mode this replaces. Our agent should adopt the same discipline with its own budget.

### 2.5 Screen capture — two independent paths

- **Path A (consent-free):** the accessibility service can take a screenshot directly (`canTakeScreenshot`).
- **Path B (MediaProjection):** `ScreenCaptureService` runs a foreground service with `foregroundServiceType="mediaProjection"`, owns an `ImageReader`, and streams frames; `RequestCastActivity` is the consent UI, and `set_stream` job actions toggle streaming on/off.

Frame transport is HTTP upload (`postFrameAsync`) with a separate HTTP client from the control plane (`frameHttp` vs `http`) — i.e. **bulk data and control are deliberately separated**.

### 2.6 Non-intrusiveness — the reference's only answer

The reference has **no** virtual display, no isolated surface, no shadow-instance rendering. Its entire non-intrusiveness strategy is:

- The backend **polls the device's state** before dispatching: the poll request carries `locked`, `secure`, and `interactive` flags (keyguard state, device-secure state, screen-interactive state).
- The backend decides, off-device, whether the device is idle enough to act.
- On-device, `isForegroundAppAllowed()` enforces an **allowlist of packages** (`Store.allowedPackages`) — the agent may not act on a foreground app outside the allowlist.
- The agent working state is persisted so the UI can be restored after unlock (`restoreWorkingAfterUnlock`).

**This is policy without isolation.** For our product — which explicitly requires "must not hijack my active touch session" — this is the central gap. See Phase 2 Q1.

---

## 3. Long-Horizon Orchestration

### 3.1 There is no on-device planner

Confirmed by evidence, not inference: the app's own class surface is **18 classes** total, and a full inventory contains **no** planner, no task/step model, no goal representation, no retry policy, no sub-agent, no tool registry, and no LLM/VLM client. The only agent-adjacent logic lives in the *actuator* and the *HITL* layers.

The entire job model is a single JSON object delivered by the server:

```
{ "job": { "jobId": "...", "action": "<primitive>", "payload": { ... } }, "feed": [ ... ] }
```

The `feed` array is an append-only activity stream (surfaced to the console as the working feed). Results are returned per job via `postResult(api, jobId, {ok: true})`.

### 3.2 The execution loop

`PollService` (foreground service, `specialUse`) runs a continuous loop:

```
while (running) {
    token = Store.token()
    if (token blank) { sleep(3000); continue }
    if (pendingPushToken != syncedPushToken) syncPushToken()
    resp = api.poll(keyguardLocked, deviceSecure, interactive)
    if (resp != null) {
        feed    -> MelayaAccessibilityService.appendWorkingFeed(...)
        job     -> dispatch by action name
                    "set_stream"     -> ScreenCaptureService.setStreaming(bool)
                    "request_mirror" -> MelayaAccessibilityService.requestScreenCast()
                                     (fallback: open the app)
    }
}
```

Observed job actions: `set_stream`, `request_mirror`, plus the general executor dispatch. The transport is a **cancellable** OkHttp call (`inFlightPoll`), so the loop can abort a poll in flight when shutting down.

### 3.3 State that survives steps

On-device persistence is `SharedPreferences` through a single `Store` object:

`token`, `baseUrl`, `allowedPackages`, `agentWorking` (label + runId), `isWorkingOverlayCollapsed`, `pendingPushToken` / `syncedPushToken`, `isPaired`.

That is the complete memory of a 20+ step run. **Run state — the plan, the step index, the observation history, the accumulated result, the budget spent — lives entirely server-side.**

### 3.4 Error handling & retries

| Failure | Handling |
|---|---|
| Poll network I/O | `IOException` swallowed only if the call was deliberately cancelled; otherwise rethrown |
| HTTP 401 | Dedicated `UnauthorizedException` |
| Action exceeded 15 s | `device_timeout` error object returned to the server |
| Action returned nothing | `no_result` |
| Accessibility service not connected | Polling continues; mirror request degrades to opening the app |
| Device locked | `locked/secure/interactive` reported so the server can defer |
| App not in allowlist | `isForegroundAppAllowed` blocks the action |

Retries, backoff, and re-planning are **not** on-device — they are the server's responsibility, which the device cannot observe directly.

### 3.5 HITL — the reference's most transferable subsystem

The reference implements a **two-channel approval protocol** that we should adopt in shape.

**Policy detection (on-device, deterministic):**
- `detectDangerous(action, payload)`, `detectPayment(...)`, `detectPublish(...)`
- Label heuristics over the tapped node: `dangerousLabelHit`, `labelIsPayment`, `labelSubmits`, `isExactSend`
- The tapped node is resolved via coordinate hit-test → `tapLabel(node)` → label classification; the node's `text` and `contentDescription` are captured as the approval payload
- `hitlMode` is normalized (`normalizeHitlMode`); a mode of `"autonomous"` short-circuits the generic dangerous-label path, with publish handled by a dedicated staged-approval path (`stagePublishApproval`, `performStagedSubmit`, `submitApprovedText`)

**Presentation (two surfaces, deliberately redundant):**
1. **In-context overlay card** — `buildApprovalCard` / `buildAskCard` rendered as an accessibility overlay anchored over the current app, with `approvalButton` actions, drag support, and `animateOverlayIn` / `animateOverlayOutThen` transitions.
2. **Notification card** — `notifyApproval` / `notifyAsk` post a notification; `ApprovalActionReceiver` / `AskReplyReceiver` receive the action taps; `resolveApprovalFromNotification` / `resolveAskFromNotification` route the decision back into the service.

**Timeout & abort:**
- `approvalTimeoutRunnable`, `askTimeoutRunnable` — every request has a bounded lifetime
- `killActiveRun` (API) and `killActiveRunFromOverlay` (an in-overlay abort button) — a kill switch exists at both transport and UI level
- `answersApproval`, `approvalKind` — the decision is a typed, narrow value, not free text

**Non-blocking semantics:** the approval request is *staged*, not blocking the executor. The step is held, the user is notified, and the decision is applied when it arrives. This is the right shape for a long-horizon agent — an approval must not freeze the run's executor thread.

---

## 4. Communication Protocol

### 4.1 Four distinct channels

| Channel | Transport | Direction | Payload |
|---|---|---|---|
| **Control plane** | HTTPS long-poll `GET {baseUrl}/api/v1/phone/poll?locked&secure&interactive`, `Authorization: Bearer <token>` | server → device | `{job, feed}` |
| **Command result** | HTTPS POST per job (`publishDecision`, `postResult`, `askAnswer`, `claim`, `killActiveRun`) | device → server | decision / approval |
| **Bulk frame channel** | Separate OkHttp client (`frameHttp`) POSTing image bytes (`postFrameAsync`) | device → server | frame blobs (live spectating) |
| **Push wake** | Firebase Cloud Messaging (`MelayaMessagingService`, `FirebaseMessagingService`, channel `melaya_broadcast`) | server → device | wake / token sync |
| **Local bridge** | Capacitor `PluginMethod` calls from the WebView (Ionic JS → `MelayaPhonePlugin`) | in-process | status, pairing, mirror, indicator, settings deep-links |

The **split between the control client and the frame client** (two separate OkHttp instances) is a deliberate and important pattern: a 2 MB frame upload must never head-of-line-block the 15 s control poll.

### 4.2 Local bridge surface (`MelayaPhonePlugin`)

`getStatus`, `setAgentWorking`, `pair`, `startMirror`, `stopMirror`, `screenCaptureResult`, `openAccessibilitySettings`, `openRestrictedSettings`, `requestBackgroundExemption`, `isBackgroundAllowed`, `returnToMelaya`, `googleSignIn`, `setApiBase`, `openExternalUrl`.

This is the console↔device control surface. Note `setAgentWorking(active, label, runId)` — the **web layer drives the native indicator**, i.e. the on-screen "agent is working" state is set from the remote console, not only by the device's own scheduler.

### 4.3 What is *not* present

- **No MCP client** in the app. No MCP server either.
- **No gRPC.**
- **No on-device LLM/VLM client, no model runtime, no prompt construction.**
- The WebSocket usage observed in the bundled web console (`wss://wss.melaya.org`, `/ws/private`, `/ws/ohlcv`, …) belongs to the **operator console's** market/data features, not to the phone-execution protocol. The phone protocol is long-poll HTTP.

### 4.4 Authentication & pairing

Bearer token + base URL stored in `Store`; pairing is initiated by a deep link into `MainActivity` (`handlePairIntent`, `handleMelayaDeepLink`, `openAppRoute` with a route + URI + args). Google sign-in via `androidx.credentials` (`CredentialProviderPlayServicesImpl`).

---

## 5. The Active-Status Indicator (reference pattern)

The reference already implements a two-part indicator, which is directly relevant to our Mode A / Mode B / Mode C requirement.

**`BorderPulseView` — edge comet trail (Mode A, edge-glow):**
- A closed `Path` around the view perimeter, with the path length measured as `perimeter`
- A travelling head advances by `phase`; the trail is drawn as **34 discrete halo dots** spaced at `perimeter * 0.0052`, each fading linearly (`a = 1 - i/34`)
- Each dot is a two-layer radial: a **purple-blue halo** (`argb(α, 150, 110, 255)`) with a shadow layer up to `dp(11)`, and a **near-white core** (`argb(α, 235, 245, 255)`) with a `dp(5)` shadow that only appears once `a > 0.45`
- Radii scale with the fade: halo `dp(2.4) * (1.25a + 0.55)`, core `dp(1.5) * (0.5 + ca)`
- Driven by a `ValueAnimator` with `onAttachedToWindow` / `onDetachedFromWindow` lifecycle hooks

**`WorkingAuraView` — compact aura (Mode B, status dot):** the same two-layer radial language at smaller scale.

**Overlay behaviours:** `showPersistentWorkingOverlay(label)` / `hideWorkingOverlay` / `pulseWorkingOverlay(text)` / `showBorderPulse` / `hideBorderPulse`; the working overlay is **expandable and collapsible** (`buildExpandedWorkingOverlay`, `buildCollapsedWorkingOverlay`, `isWorkingOverlayCollapsed`), **draggable** (`makeOverlayDraggable` via `MotionEvent`), and **auto-collapses** on a timer (`autoCollapseRunnable`).

**Mode C (off)** is implemented by `setAgentWorking(active=false)` → `clearAgentWorking` → `hideWorkingOverlay`; `setAgentWorking` is idempotent against a `runId`, so a stale console cannot clear a newer run's indicator.

**Gap vs. our requirement:** the reference's indicator is a `Canvas` comet with `setShadowLayer` blur. It reads as a glow but has **no refraction, no true blur-behind, no depth layering** — the 3D Liquid Glass aesthetic in our brief is a net-new rendering axis, not a re-skin.

---

## 6. Architectural Patterns Worth Stealing (clean-room)

1. **Single dispatch funnel.** One `execute(action, payload)` entry point for every primitive, returning uniform JSON, running on the main thread with a bounded latch. Uniformity is what makes a 100-step run tractable.
2. **Action + observation as one round trip.** The result of a mutating action carries the post-action screen state.
3. **Event-driven settle detection** (160 ms quiet / 90 ms no-change / ≤2 node delta / 25 ms poll / 1.5–2 s cap) instead of sleeps.
4. **Relative coordinates resolved on-device**, so the planner is resolution-agnostic.
5. **Control channel ≠ bulk channel.** Separate HTTP clients so frames never block control.
6. **Two redundant HITL surfaces** (in-context overlay card + notification action), with a typed decision, a timeout, and a kill switch at both transport and UI level.
7. **Non-blocking approval staging** — the step is held, the executor is not.
8. **Device-state reporting to the scheduler** (locked / secure / interactive) so the planner can defer work until idle.
9. **Package allowlist** enforced on-device, not only in the planner.
10. **Cancellable in-flight transport** (`inFlightPoll`) for clean shutdown.
11. **Idempotent indicator updates keyed by `runId`.**
12. **`canTakeScreenshot` on the accessibility service** — a consent-free capture path that avoids a recurring MediaProjection dialog.

---

## 7. Gaps in the Reference That Our Product Must Close

| # | Gap | Required by our brief |
|---|---|---|
| G1 | **No on-device planner.** Jobs are single primitives from a server. | "15–100+ chained actions from a single prompt" ⇒ we need a local Planner–Actor–Critic that owns the goal, decomposes it, and persists it. |
| G2 | **No run memory.** Only SharedPreferences scalars; no step index, no observation log, no result accumulation. | "state memory across 20+ steps" ⇒ a durable, quota-accounted step/graph store. |
| G3 | **No isolation.** The agent drives the live foreground app. | "must not hijack my active touch session" ⇒ idle-gating **plus** an isolated surface, or a hard "no touch while user active" contract. |
| G4 | **No resource governance.** No RAM cap, no storage quota, no thermal/CPU throttle, no bounded worker pool. | 2 GB RAM cap, 20 GB sandbox, CPU/battery throttling ⇒ a quota watchdog is a first-class subsystem, not a nice-to-have. |
| G5 | **No live spectating.** Frames are uploaded ad hoc; there is no real-time console view, no reasoning trace, no step counter, no instant takeover. | Live Spectating Mode with instant manual takeover. |
| G6 | **No glassmorphism.** Canvas comet + shadow blur only. | 3D Liquid Glass with refraction/specular/depth and three indicator modes (edge glow / status dot / off). |
| G7 | **No VLM grounding.** Tree-only; brittle on canvas-rendered or image-only UI. | Needs a defined fallback path (VLM/OCR) for tree-poor screens. |
| G8 | **No on-device inference.** Every decision is a network round-trip. | Needs a local brain tier to work offline, under quota, and without latency. |
| G9 | **No sandbox boundary.** `usesCleartextTraffic="true"`, `allowBackup="false"`, but no app-scoped isolated workspace with path validation. | 20 GB app-scoped sandbox; never read/write outside without permission. |
| G10 | **No agent-side MCP client.** | Our brief names MCP tool calling as a first-class protocol. |

---

## 8. Evidence Index

| Claim | REA call | Artifact |
|---|---|---|
| Package, versions, class/resource counts, permissions | `inspect-android-package` | `org.melaya.phone`, 4,985 classes, 3,097 resources, 15 permissions, manifest `complete` |
| Full class inventory | `search-android-classes <apk> ""` | 4,985 names; absence of VLM/OCR/native/Compose/Flutter/React |
| Manifest, components, meta-data | `decode-android-resources` (apktool 2.12.1) | decoded manifest, `coverage: complete` |
| Accessibility config | apktool resource decode | `res/xml/accessibility_service_config.xml` |
| Action dispatch, latch, observe-after-action | `inspect-android-method MelayaAccessibilityService.execute` | Java, `fell_back: false` |
| Settle algorithm | `…settledTree`, `…waitUiQuiet` | Java |
| HITL policy | `…detectDangerous` | Smali fallback (JADX) |
| Transport contract | `inspect-android-method Api.poll` | Java; `/api/v1/phone/poll?locked&secure&interactive` |
| Execution loop | `inspect-android-method PollService.loop` | Java |
| Indicator rendering | `inspect-android-method BorderPulseView.onDraw` | Java; 34-dot comet, halo/core paints |
| Indicator control | `inspect-android-method MelayaPhonePlugin.setAgentWorking` | Java; runId-idempotent |
| Call graph | `trace-android-references Api` | 10 refs: `MelayaAccessibilityService`, `PollService`, `MelayaMessagingService`, `MelayaPhonePlugin`, `ScreenCaptureService` |
| Local bridge | `inspect-android-class MelayaPhonePlugin` | 14 `@PluginMethod` surface |
| Hybrid shell | apktool asset decode | `capacitor.config.json`, `native-bridge.js` |

**Limitations disclosed by the evidence:** no signature verification was performed (`signature_verification: not_performed`); `detectDangerous` decompiled to Smali rather than Java (JADX fallback, semantics read from bytecode); reflection and dynamic loading remain unresolved by static analysis; the backend that produces jobs was not in scope and was not analysed; the bundled web console was inspected for structure only.
