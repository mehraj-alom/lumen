# CODING_STANDARDS.md — LUMEN

**Status:** Draft for review · **Spec version:** 1.0 · **Codename:** LUMEN (placeholder)

Enforced by `ktlint`, `detekt`, Android Lint, and the `:core:dependencyGuard` task. A rule that is not machine-checkable is marked **[manual]** and is a review duty.

---

## 1. Kotlin

### 1.1 Language level

| Rule | Detail |
|---|---|
| Language level | Kotlin 2.0, `explicitApi` **on** for `core/*` public declarations |
| Nullability | No `!!`. No platform types in a public API. `lateinit` only for `ViewModel`-scoped Android refs, always null-checked before use |
| Result handling | Expected failures are `Result<T>` or a sealed `Outcome`. Exceptions are for bugs, not for control flow |
| Sealed hierarchies | `Action` and `Assertion` are sealed interfaces with exhaustive `when`. `Phase`, `StepState`, `RiskClass`, `Verdict`, `ErrorCode` are enums with `@SerialName` on every entry, so the wire form is snake_case and never drifts with a rename |
| Data classes | `copy()` is not used to change invariants; use an explicit method with a name that says what changed |
| Extension functions | Only in `core/model` and for pure transformations. An extension that needs a `Context` is a smell |
| Scope functions | `let`/`run`/`apply`/`also` — at most one per expression, never nested more than two deep |
| Companion objects | Constants only. No mutable state in a companion |
| Object declarations | Only for stateless singletons with no lifecycle. Anything with state is a class with an explicit owner |

### 1.2 Naming

| Kind | Convention | Example |
|---|---|---|
| Class / interface | `PascalCase` | `SettleDetector` |
| Function / property | `camelCase` | `awaitSettled` |
| Constant | `UPPER_SNAKE` | `MAX_STEPS_HARD` |
| Composable | `PascalCase`, noun phrase | `GlassSurface` |
| `Composable` with a modifier | Modifier is the first parameter and is required | `fun ApprovalCard(modifier: Modifier = Modifier, ...)` |
| Test class | `SubjectUnderTestTest` | `QuotaWatchdogTest` |
| Test function | `given_When_Then` sentence | `givenFullRamBudget_whenReserveExceedsLimit_thenDenies()` |

### 1.3 Forbidden

- `GlobalScope`, `CoroutineScope(Dispatchers.IO)` created ad hoc, `Thread { }`, `AsyncTask`, `HandlerThread` for app logic.
- `println`, `print`, `System.out`.
- `TODO()` without an owner and a milestone reference. A `TODO` without a `TASK.md` id is a lint error.
- `TODO` comments left in merged code.
- Magic numbers in a composable or a policy. Use a named constant from the token file or a policy table.
- Reflection, unless there is no alternative and it is documented in an ADR.
- `!!` in any expression, including test code.

---

## 2. Concurrency

### 2.1 Structured concurrency

```kotlin
// Correct: the scope is owned by a lifecycle with a cancel method.
class AgentRuntime(private val parent: CoroutineScope) {
    suspend fun run(goal: Goal) = coroutineScope {
        // every child is a child of this scope
    }
}
```

| Rule | Detail |
|---|---|
| Scope ownership | Every `CoroutineScope` has a named owner with a `cancel()` path. A scope created in a function body must be awaited before the function returns |
| Dispatchers | Injected, never hard-coded. `core/*` takes a `CoroutineDispatcher` parameter; `Dispatchers.IO` is only referenced in `platform/*` |
| Main thread | Only `platform/*` and the UI layer touch `Dispatchers.Main`. The agent loop never runs on it |
| Cancellation | Every long-running call is cancellable. `delay`, `withContext`, and network calls are all cancellable; a blocking call must be wrapped in `runInterruptible` |
| Timeouts | Every external call has a timeout. An unbounded call is a lint error |
| `Mutex` over `synchronized` | Coroutines only |
| `Flow` | Cold flows are the default. `StateFlow` only for state that has exactly one current value |
| Backpressure | `channel(capacity = N)` with an explicit overflow policy. `UNLIMITED` is banned outside the frame buffer |
| `SupervisorJob` | Used for the runtime scope, so one failed child does not kill the run. Failures are still reported |

### 2.2 Thread count

At most **4** agent worker threads (not counting the UI thread and the platform's own). The `WorkerPool` enforces this with a `Semaphore` and reports its depth to the budget snapshot. A fifth concurrent worker is a quota error, not a scheduling decision.

### 2.3 Local inference

`LocalBrain` holds a `Semaphore(1)`. Model calls are never concurrent. A call that arrives while another is running waits with a timeout, and the timeout escalates to the cloud rather than queueing indefinitely.

---

## 3. Resource lifecycle

### 3.1 The rule

**Every resource has exactly one owner, and the owner's `close`/`release` is always reached.** Resources include: `Closeable`, `AutoCloseable`, `Cursor`, `ParcelFileDescriptor`, `Bitmap`, `InputStream`, `OutputStream`, `FileChannel`, `WebSocket`, `MediaProjection`, `VirtualDisplay`, `AccessibilityNodeInfo` (recycled where supported), `Handler`/`Executor`, and coroutine `Job`s.

```kotlin
// Correct
sandbox.withWorkspaceFile("step-42.json") { file ->
    file.writeText(payload)
}
```

### 3.2 Required patterns

| Situation | Pattern |
|---|---|
| A resource used in one function | `use { }` |
| A resource held across a suspension point | Held by a class that implements `Closeable`, closed in the owner's `finally` |
| A `Bitmap` in a loop | Recycled explicitly in a `finally`; the frame buffer pool is bounded by quota |
| A `ParcelFileDescriptor` | Passed by ownership to the receiving side; the sender closes only on failure |
| A `VirtualDisplay` | Released in `VirtualDisplayManager.release()`, which is called from the service's `onDestroy` and from the run's abort path |
| A `MediaProjection` | Released before the `MediaProjection` token is invalidated; order is asserted by a test |
| A `WebSocket` | Closed in the transport's `close()`, which cancels the read loop first |

### 3.3 The frame buffer

The frame buffer is a **bounded pool**, owned by the quota watchdog, sized by `FRAME_BUFFER` reservation. A frame that cannot be allocated is dropped, not queued. The pool reports dropped-frame counts to the console.

---

## 4. Error handling

| Rule | Detail |
|---|---|
| Never swallow | An empty `catch` is a lint error. If the exception is genuinely ignorable, it is logged at `debug` with a reason |
| Never log a secret | No token, no auth header, no full user content in a log line. Screen-derived text is redacted to a length + hash |
| Error taxonomy | Every failure maps to an `ApiError(code, message, detail)`. Codes are stable and listed in `API_SPEC.md` §1 |
| Context on failure | Every caught exception carries the `runId` and `stepId` |
| Fail closed | A failure in the policy engine, the quota check, or the idle gate **denies** the action. It never falls through to "proceed anyway" |
| Retry | Retries live in one place — `RetryPolicy` — with an explicit attempt count, backoff, and a `retryable` predicate. No ad-hoc retry loops |
| Cancellation is not an error | `CancellationException` is rethrown, never caught and converted into a failure result |

---

## 5. Logging

```kotlin
private val log = LumenLog.forComponent("Actor")
log.info("step.completed", runId, stepId, "action" to "tap", "durationMs" to 184)
```

| Rule | Detail |
|---|---|
| Structure | Key-value pairs, never a formatted string |
| Correlation | `runId` and `stepId` are on every run-path log line |
| Levels | `error` (something broke), `warn` (recoverable, degraded), `info` (lifecycle), `debug` (detail), `trace` (sporadic) |
| Redaction | `LumenLog` redacts any value whose key is in the redaction set (`text`, `content`, `body`, `screenshot`, `token`, `authorization`) |
| Volume | Run-path logging is at `info` or below in release. `debug`/`trace` are compiled out of release builds |
| No PII in the console | The console shows the action kind, the app, and the duration — not the screen text, unless the user opened the step detail |

---

## 6. Sandbox path validation

### 6.1 The rule

**All file I/O goes through `SandboxPath`.** There is no `File("...")` in app code outside `core/sandbox`. Detekt enforces this with a custom rule.

```kotlin
// Forbidden
File(context.filesDir, "frames/$name.jpg").writeBytes(bytes)

// Correct
sandbox.write(SandboxPath.of("frames/$name.jpg"), bytes)
```

### 6.2 The validator

`SandboxPath.of(relative)` must:

1. Reject an absolute path.
2. Reject any path containing `..` as a segment.
3. Reject a path containing NUL or a control character.
4. Reject a path on a foreign filesystem (`/proc`, `/sys`, `/data/data/<other>`, `/sdcard`).
5. Canonicalise the resolved path and assert it is still under the sandbox root.
6. Assert no component of the path is a symlink that escapes the root.
7. Reject a Windows-style drive prefix or UNC path (defence in depth).
8. Account the write against the bucket budget for the target area, and refuse if over budget.

**Every new I/O path ships a test** that asserts each rejection case. `TESTING.md` §3 lists the required cases.

### 6.3 Reads

Reads are also sandboxed. The only sanctioned reads outside the sandbox are:

- The user's explicitly granted media or documents, via the system picker (`ACTION_OPEN_DOCUMENT`), which returns a URI the user chose.
- App-internal shared preferences, for settings only.

The agent never enumerates or reads arbitrary app data. `QUERY_ALL_PACKAGES` is used only to resolve a launchable app by package name, and the result is not persisted beyond the run.

---

## 7. Android specifics

| Rule | Detail |
|---|---|
| Context | Use the application context except in an Activity or Service lifecycle. Never store an Activity in a field of a long-lived object |
| Manifest | Every service, receiver, and provider declares `android:exported` explicitly. Every intent filter is reviewed |
| Permissions | No new permission without explicit user approval. The permission list is asserted by a test against a checked-in expected set, so an accidental addition fails the build |
| Foreground services | Started with a visible notification within 5 s. The `startForeground` type matches the manifest declaration |
| Accessibility | The service is the only place that touches `AccessibilityService` APIs. Business logic uses `GroundingPort` |
| Overlays | `TYPE_ACCESSIBILITY_OVERLAY` only, from the accessibility service. `TYPE_APPLICATION_OVERLAY` requires `SYSTEM_ALERT_WINDOW` and is not used |
| Threads | The agent process is `nice`d. Never `SCHED_FIFO`, never a real-time priority |
| Work | `WorkManager` is for keepalive only, never for the agent loop |
| Serialization | `kotlinx.serialization` with explicit `@SerialName` on every field. The wire format is a contract |
| Compose | Composables are side-effect free. Side effects go in `LaunchedEffect`/`DisposableEffect` with a `remember`ed key |
| Compose state | State is hoisted. A composable that owns mutable state that outlives it is a bug |

---

## 8. Testing standards

| Rule | Detail |
|---|---|
| Ownership | A test lives in the module that owns the code, not in a shared test module |
| No Android in `core` | `core/*` tests are plain JVM tests. If a core test needs Android, the abstraction leaked |
| Naming | `givenX_whenY_thenZ` |
| No sleeps | Use `kotlinx-coroutines-test` virtual time. `Thread.sleep` in a test is a lint error |
| No real network | Network is faked at the transport boundary. A test that opens a socket fails the build |
| No real clock | The clock is injected. `System.currentTimeMillis()` in production code must go through `LumenClock` |
| Coverage | Every new branch in `core/policy`, `core/quota`, `core/sandbox`, and `core/agent` has a test. Coverage is enforced on those modules |
| Quota tests | Any change touching a budget has a test that asserts the exact number, not a range |
| Cancellation | Every new loop has a test that cancels it and asserts the coroutine completes |
| Migrations | Every Room schema change has a migration test that creates the old schema, migrates, and asserts the data |

---

## 9. Git and review

| Rule | Detail |
|---|---|
| Commits | Conventional Commits: `feat(core): add settle detector`, `fix(quota): release reservation on eviction` |
| One concern | A commit changes one thing. A refactor and a behaviour change are two commits |
| ADRs | Any change to a locked decision, a quota, a permission, a dependency, or a public contract gets an ADR in `docs/adr/` |
| Quota impact | Every PR description states the RAM and storage impact of the change, in MiB |
| Review checklist | The `AGENTS.md` §6.3 checklist is the review gate |

---

## 10. Lint configuration (the machine-checkable subset)

| Check | Rule |
|---|---|
| `ktlint` | Standard rules, 4-space indent, no wildcard imports, trailing commas |
| `detekt` | `LongParameterList` (max 6), `TooManyFunctions` (max 30 per class), `ReturnCount` (max 3), `ComplexMethod` (max 12), `ForbiddenComment` (TODO without a `TASK.md` id), `MaxLineLength` (140) |
| `detekt` custom | `NoGlobalScope`, `NoPrintln`, `NoThreadConstruction`, `NoRawFileConstruction` (bans `File(` outside `core/sandbox`), `NoMagicNumberInComposable` |
| `detekt` custom | `NoPlatformTypeInPublicApi` (bans `Bundle!`, `Context!`, etc. in `core/*` signatures) |
| `dependencyGuard` | Fails if `core/*` gains a dependency on `androidx.*`, `android.*`, or any module outside `core/` |
| `dependencyGuard` | Fails if a new third-party dependency appears that is not in the `TECHSTACK.md` §10 ledger |
| Android Lint | `MissingPermission`, `ForegroundServiceType`, `ExportedReceiver`, `InlinedApi` are errors |
| Custom lint | `MissingPermissionAssertion` — the expected permission set is a checked-in file; an addition fails the build |
| Custom lint | `TokenUsage` — a magic number in a composable fails the build |
