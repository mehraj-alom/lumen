# API_SPEC.md — LUMEN

**Status:** Draft for review · **Spec version:** 1.0 · **Codename:** LUMEN (placeholder)

Four contract families: on-device IPC (AIDL), the brain tool schema, the MCP client contract, and the state/frame streaming payloads. Everything here is versioned; breaking changes require an ADR.

---

## 1. Conventions

| Rule | Detail |
|---|---|
| Naming | `PascalCase` for types, `camelCase` for fields, `snake_case` for JSON keys, `UPPER_SNAKE` for enum wire values |
| Identifiers | `runId` and `stepId` are ULID strings (26 chars, lexicographically sortable, no dependency) |
| Timestamps | Unix epoch **milliseconds**, UTC. Wall-clock is for display only; ordering uses the monotonic clock captured in `seq` |
| Ordering | Every event carries a monotonic `seq` per run. Consumers order by `seq`, never by timestamp |
| Errors | `{"error": {"code": "...", "message": "...", "detail": {...}}}` — `code` is a stable machine string |
| Pagination | Cursor-based, `{"cursor": "...", "limit": N}` → `{"items": [...], "nextCursor": "..."}` |
| Idempotency | Mutating endpoints accept an `Idempotency-Key` header. Repeating a key returns the original result |

**Error codes (stable):**

| Code | Meaning |
|---|---|
| `not_found` | Unknown run / step / approval id |
| `invalid_state` | The operation is not legal in the run's current state |
| `denied_by_policy` | The `PolicyEngine` refused the action |
| `approval_required` | The action is staged and waiting for a human |
| `approval_denied` | A human refused the action |
| `approval_expired` | The approval lease expired |
| `quota_exceeded` | A RAM / storage / step / time / cost budget is exhausted |
| `aborted` | The run was aborted |
| `accessibility_unavailable` | The accessibility service is not enabled or was revoked |
| `idle_gate_blocked` | The device is in use and the live surface is not permitted |
| `grounding_unavailable` | No usable screen observation could be produced |
| `unsettled` | The screen did not settle within the cap |
| `brain_unavailable` | No brain (local or cloud) could serve the request |
| `sandbox_violation` | A path escaped the sandbox root |
| `internal` | A bug. Always carries a correlation id |

---

## 2. On-device IPC — `IAgentRuntime.aidl`

The console (`:app`) talks to the agent runtime (`:agent`) over a bound AIDL service. The agent process is the authority; the console is a view.

```java
// IAgentRuntime.aidl
package dev.lumen.agent.ipc;

import dev.lumen.agent.ipc.AgentState;
import dev.lumen.agent.ipc.ApprovalRequest;
import dev.lumen.agent.ipc.BudgetSnapshot;
import dev.lumen.agent.ipc.RunHandle;
import dev.lumen.agent.ipc.StartGoalRequest;
import dev.lumen.agent.ipc.StepEvent;

interface IAgentRuntime {
    // --- control ---
    RunHandle startGoal(in StartGoalRequest request);
    void pauseRun(String runId, String reason);
    void resumeRun(String runId);
    void abortRun(String runId, String reason);
    void answerApproval(String runId, String approvalId, int decision, String answerText);
    void setBrainPin(String runId, int pin);   // 0 = AUTO, 1 = LOCAL, 2 = CLOUD
    void setTakeover(String runId, boolean takenOver);

    // --- observation ---
    AgentState getState(String runId);
    BudgetSnapshot getBudget(String runId);

    // --- streams (one-shot register, server pushes) ---
    void registerStateCallback(IStateCallback callback);
    void registerFrameCallback(IFrameCallback callback);
    void unregisterStateCallback(IStateCallback callback);
    void unregisterFrameCallback(IFrameCallback callback);
}

interface IStateCallback {
    oneway void onStepEvent(in StepEvent event);
    oneway void onStateChanged(in AgentState state);
    oneway void onApprovalRequired(in ApprovalRequest request);
    oneway void onError(String code, String message, String correlationId);
}

interface IFrameCallback {
    oneway void onFrame(in FrameChunk frame);
    oneway void onFrameStats(int droppedFrames, double fps);
}
```

### 2.1 Payloads

```kotlin
// AgentState
data class AgentState(
    val runId: String,
    val phase: Phase,              // IDLE | PLANNING | RUNNING | WAITING_APPROVAL | PAUSED | DONE | FAILED | ABORTED
    val stepIndex: Int,            // completed steps
    val stepCount: Int,            // planned steps
    val currentStep: StepSummary?,
    val planVersion: Int,
    val indicatorMode: IndicatorMode,  // EDGE_GLOW | STATUS_DOT | OFF
    val indicatorState: IndicatorState,// IDLE | WORKING | AWAITING_APPROVAL | BLOCKED | ERROR | ABORTING
    val brainRoute: BrainRoute,    // LOCAL | CLOUD | AUTO_RESOLVED_LOCAL | AUTO_RESOLVED_CLOUD
    val takeoverActive: Boolean,
    val lastError: ApiError?
)

// StepSummary
data class StepSummary(
    val stepId: String,
    val seq: Long,
    val intent: String,
    val reason: String,            // I9 — mandatory
    val riskClass: String,         // H1..H10
    val state: String,             // PLANNED | RUNNING | OBSERVED | COMPLETE | FAILED | DENIED | SKIPPED
    val app: String?,              // target package
    val startedAtMs: Long,
    val durationMs: Long?
)

// ApprovalRequest
data class ApprovalRequest(
    val runId: String,
    val approvalId: String,
    val stepId: String,
    val riskClass: String,         // H1..H10
    val title: String,             // "Send message to Ana"
    val detail: String,            // concrete content: the text, the amount, the item
    val app: String,
    val appLabel: String,
    val consequences: List<String>,// irreversible effects, spelled out
    val actionPreview: ActionPreview,
    val expiresAtMs: Long,
    val leaseSeconds: Int
)

// ActionPreview — a redacted, human-readable projection of the action
data class ActionPreview(
    val kind: String,              // TAP | INPUT_TEXT | CLICK_BY_ID | OPEN_APP | ...
    val label: String?,            // "the 'Pay €382' button"
    val text: String?,             // the text that will be typed
    val target: String?            // package/activity
)

// BudgetSnapshot
data class BudgetSnapshot(
    val ramUsedMiB: Int, val ramLimitMiB: Int, val ramByCategory: Map<String, Int>,
    val storageUsedMiB: Int, val storageLimitMiB: Int, val storageByBucket: Map<String, Int>,
    val stepsUsed: Int, val stepsSoftLimit: Int, val stepsHardLimit: Int,
    val elapsedMs: Long, val wallClockLimitMs: Long,
    val costMicros: Long, val costLimitMicros: Long,
    val thermalStatus: Int, val batteryPercent: Int
)

// FrameChunk — bulk channel, separate from control
data class FrameChunk(
    val runId: String,
    val seq: Long,
    val width: Int, val height: Int,
    val format: String,            // "JPEG" | "PNG" | "WEBP"
    val bytes: ByteArray,          // written to a ParcelFileDescriptor, not inlined
    val source: String             // "ACCESSIBILITY_SCREENSHOT" | "MEDIA_PROJECTION" | "VIRTUAL_DISPLAY"
)
```

**Frames are transferred by `ParcelFileDescriptor`**, never inlined into a Binder transaction — a 4 MB frame exceeds the 1 MB Binder limit and would throw `TransactionTooLargeException`. The descriptor is written to a temp file inside the sandbox and the receiver reads it through `SandboxPath`.

### 2.2 The state stream

```json
{
  "type": "step.completed",
  "runId": "01JBX...", "seq": 142, "tsMs": 1760000000000,
  "stepId": "01JBX...", "planVersion": 3,
  "intent": "Open the second search result",
  "reason": "The first result was an aggregator, not an airline site",
  "riskClass": "NONE", "app": "com.example.browser", "durationMs": 1840
}
```

Event types: `run.started`, `run.plan_created`, `run.plan_revised`, `step.started`, `step.observed`, `step.completed`, `step.failed`, `step.denied`, `approval.requested`, `approval.resolved`, `brain.route_changed`, `budget.warning`, `budget.exhausted`, `run.paused`, `run.resumed`, `run.completed`, `run.failed`, `run.aborted`, `trace.note`.

---

## 3. Brain tool schema

The planner and critic are given a tool schema. They may only emit these tools. The `Actor` is the only component that can execute one.

```json
{
  "type": "function",
  "function": {
    "name": "observe_screen",
    "description": "Read the current screen. Returns the accessibility tree, the foreground app, and optionally a screenshot. Call this before deciding what to interact with.",
    "parameters": {
      "type": "object",
      "properties": {
        "include_screenshot": { "type": "boolean", "default": false },
        "include_overlays":  { "type": "boolean", "default": true },
        "max_nodes":        { "type": "integer", "default": 400, "maximum": 1200 }
      },
      "required": []
    }
  }
}
```

The complete tool set:

| Tool | Kind | Notes |
|---|---|---|
| `observe_screen` | read | The only way to see. Screenshot is optional and budgeted. |
| `tap` | act | Relative coords `x`,`y` in `0.0..1.0`, or `target_text` / `target_id` |
| `double_tap` | act | |
| `long_press` | act | `duration_ms` |
| `swipe` | act | `from_x`,`from_y`,`to_x`,`to_y`,`duration_ms` |
| `scroll` | act | `direction` = up/down/left/right, `amount` |
| `input_text` | act | `text` — always classified; typing a credential is H4 |
| `press_enter` | act | |
| `open_app` | act | `package` — must be on the allowlist |
| `open_url` | act | `url` — scheme allowlist |
| `list_apps` | read | Installed, launchable packages (respects `QUERY_ALL_PACKAGES` policy) |
| `read_workspace_file` | read | Sandbox path only |
| `write_workspace_file` | act | Sandbox path only, sandbox-validated |
| `request_approval` | control | The planner may *ask*; it cannot approve |
| `finish` | control | Goal complete, with a `summary` |
| `abort` | control | The planner may give up, with a `reason` |

**Tool-call envelope** (what the brain must return):

```json
{
  "plan_version": 3,
  "thought": "The fare on site A is €382 but excludes a bag; site B is €351 with a bag. I should pick site B.",
  "steps": [
    { "seq": 1, "intent": "Open the results list", "reason": "Prices are only visible on the results page",
      "action": { "tool": "tap", "args": { "target_text": "Prices" } },
      "expect": { "any_text": ["Departure", "Total price"] }, "risk_class": "NONE" }
  ]
}
```

**Enforced server-side** (the brain's output is data, not authority):

- `risk_class` in the model output is **ignored**; the `PolicyEngine` classifies.
- `action.args` outside the schema are rejected.
- Coordinates outside `0.0..1.0` are rejected.
- A `steps` array longer than the planner's per-call budget is truncated and the run is re-planned.
- Any `thought` is stored as the reasoning trace, but it is **redacted** before egress if it contains screen-derived content and spectating is off.

---

## 4. MCP client contract

We are an MCP client. The agent calls user-configured MCP servers.

### 4.1 Transport

| Transport | Use |
|---|---|
| stdio | Local server, launched by the **user**, not by the agent. JSON-RPC over stdin/stdout. |
| Streamable HTTP | Remote server. JSON-RPC 2.0 over HTTP with SSE or a single response. |

### 4.2 Lifecycle

```
initialize  →  notifications/initialized  →  tools/list  →  tools/call  →  …
```

```json
// initialize request
{
  "jsonrpc": "2.0", "id": 1, "method": "initialize",
  "params": {
    "protocolVersion": "2025-06-18",
    "capabilities": {},
    "clientInfo": { "name": "lumen-agent", "version": "1.0.0" }
  }
}
```

Server capabilities we use: `tools.listChanged` (we re-fetch on the notification), `logging` (we surface entries in the trace), `resources` (we do **not** use resources in v1 — file access goes through the sandbox, not a server's filesystem).

### 4.3 Tool discovery and invocation

```json
// tools/call
{
  "jsonrpc": "2.0", "id": 42, "method": "tools/call",
  "params": { "name": "search_docs", "arguments": { "query": "..." } }
}
```

Response content blocks we accept: `text`, `image` (base64), `resource` (we reject — it would be an out-of-sandbox read), `audio` (rejected).

### 4.4 MCP safety rules

| Rule | Enforcement |
|---|---|
| MCP tools are classified like any other action | `PolicyEngine.classify` runs on the tool name + arguments before `tools/call` |
| H1–H9 MCP tools require approval | The same HITL gate; the approval preview shows the server, the tool, and the arguments |
| Images returned by an MCP server are budgeted | Counted against `FRAME_BUFFER`; dropped if over budget |
| The agent never spawns an MCP server | Servers are user-launched. I5: the agent never executes code it wrote |
| The agent never writes into an MCP server's filesystem | Only `workspace/` is writable |
| Cancellation | On abort, the client sends `notifications/cancelled` and closes the transport |

### 4.5 MCP server configuration

```json
{
  "servers": [
    { "name": "files", "transport": "stdio", "command": "mcp-server-files",
      "args": ["--root", "/home/user/notes"], "enabled": true,
      "risk_classes": ["H1", "H2", "H3"] },
    { "name": "web", "transport": "http", "url": "https://mcp.example.com/mcp",
      "headers": { "Authorization": "Bearer ${MCP_TOKEN}" }, "enabled": true }
  ]
}
```

Server names are surfaced in the console and in every approval prompt, so the user always knows which third party is being invoked.

---

## 5. Cloud brain contract

```http
POST /v1/chat/completions
Authorization: Bearer ${CLOUD_BRAIN_KEY}
Content-Type: application/json
X-Run-Id: 01JBX...
X-Step-Id: 01JBX...
```

```json
{
  "model": "pinned-model-id",
  "seed": 841293,
  "temperature": 0.2,
  "max_tokens": 2048,
  "tools": [ { "type": "function", "function": { "name": "observe_screen", "...": "..." } } ],
  "messages": [
    { "role": "system", "content": "…pinned prompt, version 3…" },
    { "role": "user", "content": "Goal: …\nDevice: …\nObservation:\n…" }
  ]
}
```

**Response contract:**

```json
{
  "id": "chatcmpl-…",
  "model": "pinned-model-id",
  "choices": [{ "message": { "role": "assistant", "content": "…", "tool_calls": [
    { "id": "call_1", "type": "function", "function": { "name": "tap", "arguments": "{\"target_text\":\"Pay\"}" } }
  ] }, "finish_reason": "tool_calls" }],
  "usage": { "prompt_tokens": 3120, "completion_tokens": 260 }
}
```

**Determinism:** `seed` is persisted per step. `temperature` is pinned per call site (0.2 for the planner, 0.0 for the critic). The `model` id is pinned in config and recorded in the plan.

**Escalation contract:** when the local brain is unavailable or the critic reports low confidence, the agent re-issues the same request to the cloud **with the same prompt and seed** and records `brain.route_changed` in the trace.

---

## 6. Console ↔ backend (web spectating)

| Endpoint | Method | Purpose |
|---|---|---|
| `/v1/runs` | POST | Create a run from a high-level goal |
| `/v1/runs/{id}` | GET | Full run state |
| `/v1/runs/{id}/events` | GET | Cursor-paginated event history |
| `/v1/runs/{id}/stream` | WS | Live step events, reasoning trace, approvals, budget |
| `/v1/runs/{id}/frames` | WS | Live frame channel (separate socket) |
| `/v1/runs/{id}/approvals/{approvalId}` | POST | Answer an approval from the console |
| `/v1/runs/{id}/takeover` | POST | Take over / release |
| `/v1/runs/{id}/abort` | POST | Abort |
| `/v1/budget` | GET | Current budget snapshot |
| `/v1/settings/brain` | PUT | Set the brain pin |

**Frame channel protocol:** binary frames, one per WebSocket message, prefixed with a 32-byte header.

```
offset  size  field
0       4     magic "LMF1"
4       1     format (1=JPEG, 2=PNG, 3=WEBP)
5       1     flags
6       2     width
8       2     height
10      8     seq
18      8     tsMs
26      6     reserved
32      ...   payload
```

**Egress control:** the frame socket is only opened when spectating is enabled. When spectating is off, the device opens no frame socket at all, and the `ContentEgressGuard` refuses any frame-class payload on any channel.

---

## 7. Sandbox path contract

Every file operation is expressed as a `SandboxPath`:

```kotlin
@JvmInline
public value class SandboxPath(public val relative: String) {
    init {
        Companion.validate(relative)
    }

    public companion object {
        public fun of(relative: String): SandboxPath
        public fun validate(relative: String)
    }
}
```

The validation runs in the constructor, so **every** path is checked — there is
no way to construct one that was not validated, including by deserialisation.
The checks that need a filesystem (canonicalisation and symlink escape) are
performed by `SandboxRoot.resolve`, which knows where the root is.

| Rejected | Example |
|---|---|
| Parent traversal as a segment | `../etc/passwd`, `frames/../../etc/passwd` |
| Absolute paths | `/etc/passwd` |
| Home-relative paths | `~/secrets` |
| Control characters, including NUL | `frames/a b.jpg` |
| Foreign filesystems | `proc/self/mem`, `sdcard/DCIM/x.jpg` |
| Windows drive prefixes and UNC paths | `C:/Windows`, `\\server\share` |
| A symlink that resolves outside the root | `frames/link.txt` → `/etc/passwd` |

```json
{
  "code": "sandbox_violation",
  "message": "the path resolves outside the sandbox root",
  "detail": { "requested": "../../etc/passwd", "root": "/data/user/0/dev.lumen.agent/files/sandbox" }
}
```

**Storage buckets** and their budgets (`AGENTS.md` §2): `models/` 4,000 MiB,
`frames/` 6,000 MiB, `traces/` 3,000 MiB, `state/` 2,000 MiB, `workspace/`
4,000 MiB, 1,000 MiB of headroom. The first segment of a path names the
bucket. A write that would push a bucket past its budget evicts the
least-recently-used files in that bucket until it fits; the `workspace/`
bucket is **never** swept, so a full workspace refuses the write rather than
deleting the user's files.

---

## 8. Versioning and compatibility

| Surface | Version field | Compatibility rule |
|---|---|---|
| AIDL | `protocolVersion` in `AgentState` | The console supports the current and previous minor. A newer agent sends an unknown version → the console shows "update the console". |
| Brain tool schema | `prompt_version` on each step | A prompt version change forces a re-plan, never a silent drift |
| Event stream | `schemaVersion` in the envelope | Additive fields are allowed; removals bump the major |
| Room schema | `version` + `Migration` | Every bump ships a migration and a migration test |
| REST | URL path prefix `/v1` | Breaking → `/v2`; the console supports both for one release |

**Conformance suite:** `api/src/test` contains a golden-file test that asserts the AIDL payload shapes, the tool schema, and the event envelope against checked-in JSON samples. A change to any of these without updating the golden fails the build.
