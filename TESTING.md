# TESTING.md — LUMEN

**Status:** Draft for review · **Spec version:** 1.0 · **Codename:** LUMEN (placeholder)

---

## 1. Strategy

| Layer | What it proves | Where it runs | Gate |
|---|---|---|---|
| **Pure unit** | Logic, given inputs | JVM | `./gradlew test` |
| **Property** | Invariants over generated inputs | JVM | `./gradlew test` |
| **Golden** | Contract shapes and visuals | JVM + device | `./gradlew test` + `connectedAndroidTest` |
| **Integration** | The loop, end to end, against fakes | JVM | `./gradlew :core:loopRecoveryTest` |
| **Instrumentation** | The real accessibility service, real overlays | Device | `./gradlew connectedAndroidTest` |
| **Quota probe** | Real RAM and storage footprints | Device | `./gradlew :core:quotaProbe` |
| **Soak** | A 100-step run under real time | Device | manual, pre-release |

**Principles:**

1. `core/*` is tested on the JVM with no Robolectric. If a core test needs an Android runtime, the abstraction leaked and the design is wrong.
2. No real network. No real clock. No `Thread.sleep`. No real filesystem outside a per-test temp dir.
3. Every test names the invariant it defends. If a test cannot be summarised in one sentence, it is testing nothing.
4. Quota tests assert **exact numbers**, never ranges. A budget that drifts by 1 MiB is a regression.

---

## 2. Test fixtures and fakes

| Fixture | Purpose |
|---|---|
| `FakeGroundingPort` | Serves synthetic `TreeSnapshot`s from a scripted sequence; records dispatched actions |
| `SyntheticTreeBuilder` | Builds a realistic `TreeSnapshot` (node id, text, content desc, bounds, class, flags, children) |
| `FakeUiEventClock` | A controllable `UiEventClock` — `advanceQuiet(ms)`, `emitEvent(type)`, `emitScroll()` |
| `FakeClock` | `LumenClock` with `advance(ms)`; virtual time everywhere |
| `FakeBrain` | Returns scripted plans; can be told to fail, time out, or escalate |
| `FakeApprovalSurface` | Records approval requests; answers on demand |
| `FakeQuotaProbe` | Reports a configurable RSS/PSS/storage reading |
| `FakeStorage` | An in-memory `SandboxPath` implementation with a real size accounting model |
| `FakeTransport` | An in-memory control/bulk transport pair; can inject latency, 401, 500, truncation |
| `MutableIdGenerator` | Deterministic ids for golden assertions |
| `ScriptedScenario` | A DSL for a multi-step run: `givenScreen(...) whenStep(...) thenExpect(...)` |

**`SyntheticTreeBuilder` must model the shapes that actually break real agents:**

- A node with a text label but no clickable flag.
- A clickable node whose parent holds the text.
- A recycler item with a recycled holder whose bounds are stale.
- A canvas-rendered region with **no** accessibility node at all.
- A node whose `contentDescription` contains the visible text but whose `text` is empty.
- Two nodes with identical text and identical bounds.
- A node inside a WebView with a `chrome://` class name.
- An overlay window belonging to a different app.

---

## 3. Quota watchdog suite — `:core:quotaTest`

### 3.1 RAM

| ID | Test | Asserts |
|---|---|---|
| Q-RAM-01 | `givenEmptyBudget_whenReserveBaseline_thenSucceeds` | 64 MiB baseline is accepted |
| Q-RAM-02 | `givenFullBudget_whenReserveExceedsLimit_thenDenies` | A reservation beyond 2,048 MiB is refused |
| Q-RAM-03 | `givenReservedBudget_whenReserveModel_thenConsumesFromBrainCategory` | 1,100 MiB lands in `BRAIN_MODEL` |
| Q-RAM-04 | `givenModelWouldOverflow_whenRequestingLoad_thenRouterEscalatesToCloud` | The router never loads a model that does not fit |
| Q-RAM-05 | `givenAt95Percent_whenTick_thenEvictsFrameBuffersFirst` | Eviction order is frame buffer → tree cache → plan memory |
| Q-RAM-06 | `givenEvictionComplete_whenStillOverCap_thenUnloadsLocalModel` | The model is unloaded and the route flips to cloud |
| Q-RAM-07 | `givenAtCap_whenTick_thenHardStopsAndCheckpoints` | The run stops cleanly and the step is committed |
| Q-RAM-08 | `givenReleasedReservation_whenBudgetRecomputed_thenReturnsToBaseline` | Reservations are not leaked |
| Q-RAM-09 | `givenConcurrentReservations_whenTotalExceedsCap_thenOneIsDenied` | No over-commit under concurrency |
| Q-RAM-10 | `givenSteadyStateIdle_whenMeasured_thenFootprintWithinDeclaredBudget` | The probe reports each category under its cap |

### 3.2 Storage

| ID | Test | Asserts |
|---|---|---|
| Q-STO-01 | `givenPathInsideRoot_whenWriting_thenSucceeds` | — |
| Q-STO-02 | `givenPathWithParentTraversal_whenValidating_thenThrowsSandboxViolation` | `../` rejected |
| Q-STO-03 | `givenAbsolutePath_whenValidating_thenThrows` | `/etc/passwd` rejected |
| Q-STO-04 | `givenSymlinkEscapingRoot_whenValidating_thenThrows` | Symlink escape rejected |
| Q-STO-05 | `givenForeignFilesystemPath_whenValidating_thenThrows` | `/proc/self/mem` rejected |
| Q-STO-06 | `givenNulInPath_whenValidating_thenThrows` | — |
| Q-STO-07 | `givenBucketOverBudget_whenWriting_thenEvictsLruThenRetries` | Eviction then retry |
| Q-STO-08 | `givenBucketOverBudgetAfterEviction_whenWriting_thenThrowsQuotaExceeded` | — |
| Q-STO-09 | `givenWorkspaceFile_whenQuotaSweeps_thenWorkspaceIsNeverDeleted` | `workspace/` is exempt from eviction |
| Q-STO-10 | `givenBucketAtCap_whenMeasuring_thenUsageIsExactlyCapMiB` | Exact number |

### 3.3 CPU, thermal, battery

| ID | Test | Asserts |
|---|---|---|
| Q-THM-01 | `given42Degrees_whenGovernorTicks_thenWorkerCountDropsToOne` | — |
| Q-THM-02 | `given46Degrees_whenGovernorTicks_thenLocalInferenceSuspends` | — |
| Q-THM-03 | `givenBatteryBelow20AndNotCharging_whenGovernorTicks_thenRunsSuspend` | — |
| Q-THM-04 | `givenFourWorkerThreads_whenFifthRequested_thenDenied` | The semaphore holds |

---

## 4. Mock UI-tree navigation suite — `:core:groundingTest`

### 4.1 Tree reading and indexing

| ID | Test | Asserts |
|---|---|---|
| T-NAV-01 | `givenTreeWithNestedClickable_whenFindingByText_thenFindsTheClickableAncestor` | — |
| T-NAV-02 | `givenDuplicateLabels_whenFindingByText_thenReturnsAllOccurrences` | — |
| T-NAV-03 | `givenRecyclerItem_whenReadingBounds_thenStaleBoundsAreIgnored` | — |
| T-NAV-04 | `givenDeepTree_whenSummarising_thenFitsTheTokenBudget` | The summary is within the planner's budget |
| T-NAV-05 | `givenCanvasRegionWithNoNodes_whenGrounding_thenReportsUnusable` | The critic gets a clear signal, not an empty tree |
| T-NAV-06 | `givenWebViewNodes_whenGrounding_thenClassNamesAreRecognised` | `chrome://`/`android.webkit` are tagged |
| T-NAV-07 | `givenNodeWithContentDescOnly_whenLabelling_thenUsesContentDesc` | — |
| T-NAV-08 | `givenTreeOrdering_whenSnapshotting_thenIsCanonicallySorted` | Determinism (I8) |

### 4.2 Settle detection

| ID | Test | Asserts |
|---|---|---|
| T-SET-01 | `givenEventThenQuiet_whenAwaiting_thenReturnsAfter160ms` | — |
| T-SET-02 | `givenNoEventAtAll_whenAwaiting_thenReturnsAfter90ms` | — |
| T-SET-03 | `givenContinuousEvents_whenAwaiting_thenReturnsAtTheCap` | 1,500 ms scroll / 2,000 ms non-scroll |
| T-SET-04 | `givenStableNodeCount_whenAwaiting_thenReturnsEarly` | The ≤2 delta rule fires |
| T-SET-05 | `givenChurningNodeCount_whenAwaiting_thenDoesNotReturnEarly` | — |
| T-SET-06 | `givenStaleCachedTree_whenReturning_thenRefreshesInstead` | The 150 ms guard |
| T-SET-07 | `givenScrollEvent_whenAwaiting_thenUsesTheScrollClock` | Separate clocks |
| T-SET-08 | `givenCancellation_whenAwaiting_thenReturnsPromptly` | — |

### 4.3 Action dispatch

| ID | Test | Asserts |
|---|---|---|
| T-DSP-01 | `givenRelativeCoords_whenDispatching_thenResolvesToPixels` | Resolution independence |
| T-DSP-02 | `givenOutOfRangeCoords_whenDispatching_thenRejects` | Outside `0..1` is a validation error |
| T-DSP-03 | `givenMutatingAction_whenDispatching_thenObservationIsIncluded` | Atomicity |
| T-DSP-04 | `givenAnyAction_whenDispatching_thenPassesThroughTheSingleFunnel` | Every action goes through `ActionExecutor` |
| T-DSP-05 | `givenDragPath_whenDispatching_thenPathIsInterpolatedWithEasing` | Not a start→end fling |
| T-DSP-06 | `givenIdleGateClosed_whenDispatching_thenStepIsHeld` | No gesture reaches the live screen |
| T-DSP-07 | `givenPackageNotAllowed_whenDispatching_thenStepIsBlocked` | Allowlist enforced on every path |
| T-DSP-08 | `givenForegroundPackageChanged_whenDispatching_thenStepAborts` | The foreground guard |
| T-DSP-09 | `givenAbortSignal_whenDispatching_thenNothingIsDispatched` | I12 |
| T-DSP-10 | `givenLedgerAppendFails_whenDispatching_thenActionDoesNotRun` | The write-ahead log is a precondition |

---

## 5. Agent loop recovery suite — `:core:loopRecoveryTest`

### 5.1 The 100-step scenario

`HundredStepScenario` drives the full acceptance workload from `PRD.md` §5 through the fakes:

| ID | Test | Asserts |
|---|---|---|
| L-100-01 | `givenFullScenario_whenRunning_thenReachesCompleted` | All 100 steps, 3 HITL gates encountered |
| L-100-02 | `givenScenario_whenRunning_thenPlanIsDeterministic` | Same seed + same screen sequence ⇒ identical plan |
| L-100-03 | `givenScenario_whenRunning_thenEachStepHasAReason` | I9 |
| L-100-04 | `givenScenario_whenRunning_thenEveryMutatingStepHasAPreState` | I6 |
| L-100-05 | `givenScenario_whenRunning_thenThreeDistinctApprovalsWereStaged` | W2 — no approval covers two gates |
| L-100-06 | `givenScenario_whenRunning_thenNoScreenContentLeftTheDevice` | I11, W6 |
| L-100-07 | `givenScenario_whenRunning_thenTraceReconstructsTheRun` | W3 |
| L-100-08 | `givenScenario_whenRunning_thenWallClockStaysWithinBudget` | W7 |
| L-100-09 | `givenScenario_whenRunning_thenPeakRamStaysWithinBudget` | W8 |
| L-100-10 | `givenScenario_whenRunning_thenIndicatorIsVisibleOnlyDuringActivePhases` | W9 |

### 5.2 Recovery

| ID | Test | Asserts |
|---|---|---|
| L-REC-01 | `givenProcessDeathMidStep_whenResuming_thenContinuesFromTheNextStep` | W1 |
| L-REC-02 | `givenTargetAppCrash_whenStepFails_thenCriticReplans` | — |
| L-REC-03 | `givenScreenNeverSettles_whenStepRuns_thenStepReturnsUnsettled` | — |
| L-REC-04 | `givenEmptyGrounding_whenStepRuns_thenUsesVlmFallbackIfEnabled` | — |
| L-REC-05 | `givenVlmDisabled_whenGroundingEmpty_thenStepFails` | — |
| L-REC-06 | `givenLocalBrainCrash_whenPlanning_thenEscalatesToCloud` | — |
| L-REC-07 | `givenCloudUnreachable_whenPlanning_thenFallsBackToLocal` | — |
| L-REC-08 | `givenBothBrainsDown_whenPlanning_thenRunPausesWithAClearReason` | N5 |
| L-REC-09 | `givenBudgetExhausted_whenLoopContinues_thenRunStopsCleanly` | — |
| L-REC-10 | `givenUserTakeover_whenAgentNextActs_thenAgentIsPaused` | ≤ 1 s |
| L-REC-11 | `givenUserTouchOnLiveSurface_whenAgentNextActs_thenAgentIsPaused` | I3 |
| L-REC-12 | `givenApprovalTimeout_whenLeaseExpires_thenStepIsDenied` | — |
| L-REC-13 | `givenDenyAnswer_whenStepResumes_thenStepIsDeniedAndNotRetried` | — |
| L-REC-14 | `givenCrashDuringReplan_whenResuming_thenPlanVersionIsConsistent` | — |
| L-REC-15 | `givenStorageFullDuringTraceWrite_whenWriting_thenEvictsLruAndRetries` | — |

### 5.3 HITL policy

| ID | Test | Asserts |
|---|---|---|
| L-HIT-01 | `givenPaymentSurface_whenClassifying_thenMatchesH1` | — |
| L-HIT-02 | `givenPublishAffordance_whenClassifying_thenMatchesH2` | — |
| L-HIT-03 | `givenDeleteButton_whenClassifying_thenMatchesH3` | — |
| L-HIT-04 | `givenCredentialField_whenClassifying_thenMatchesH4` | — |
| L-HIT-05 | `givenPermissionDialog_whenClassifying_thenMatchesH5` | — |
| L-HIT-06 | `givenNoMatch_whenClassifying_thenDoesNotMatch` | No false positives on read-only steps |
| L-HIT-07 | `givenAutonomousMode_whenClassifying_thenDoesNotLoosenThePolicy` | The brain cannot loosen the policy |
| L-HIT-08 | `givenPlannerSaysDangerous_whenClassifying_thenEscalatesToAsk` | The brain can only tighten |
| L-HIT-09 | `givenTimeout_whenResolving_thenDenies` | Default deny |
| L-HIT-10 | `givenApprovalStaged_whenProcessDies_whenResuming_thenApprovalIsResurfaced` | — |
| L-HIT-11 | `givenGrantIssuedForOneClass_whenDifferentClassUsed_thenGrantIsRejected` | Grants are bound to one action class and one run |
| L-HIT-12 | `givenMcpToolWithPaymentName_whenClassifying_thenStillMatchesH1` | MCP tools are not exempt |

---

## 6. Golden contract tests

| ID | Suite | Asserts |
|---|---|---|
| G-01 | `AidlPayloadGoldenTest` | Every AIDL payload serialises to the checked-in JSON sample |
| G-02 | `ToolSchemaGoldenTest` | The brain tool schema matches the checked-in sample |
| G-03 | `EventEnvelopeGoldenTest` | The event envelope matches |
| G-04 | `RoomSchemaGoldenTest` | The generated Room schema matches the checked-in `schema.json` |
| G-05 | `FrameHeaderGoldenTest` | The 32-byte frame header layout matches |
| G-06 | `PermissionSetGoldenTest` | The merged manifest permission set matches the checked-in expected set |

---

## 7. Instrumentation tests (device required)

| ID | Test | Asserts |
|---|---|---|
| I-01 | `AccessibilityServiceIsEnabled` | The service connects and reports its config |
| I-02 | `RealGestureInjection` | A tap lands on the right node in a test app |
| I-03 | `RealSettleDetection` | The settle detector's timings match the constants against a real UI |
| I-04 | `CanTakeScreenshot` | The accessibility screenshot path returns a non-blank bitmap |
| I-05 | `OverlayRendersAboveApp` | The indicator and approval card draw above the foreground app |
| I-06 | `VirtualDisplayFallback` | When the target app refuses a secondary display, the step falls back to the live surface if the idle gate allows |
| I-07 | `IdleGateBlocksLiveSurface` | With simulated touch, no gesture is dispatched |
| I-08 | `ForegroundServiceStaysAlive` | The agent process survives the console being destroyed |
| I-09 | `BootReceiverResumesPendingRun` | A resumable run is re-armed after boot |
| I-10 | `GlassShaderRenders` | The AGSL shader compiles and produces a non-uniform bitmap |
| I-11 | `IndicatorModesRender` | Each of the three indicator modes renders as expected |
| I-12 | `RoomMigrationHolds` | Every historical schema version migrates with data intact |
| I-13 | `QuotaProbeWithinBudget` | The real footprint matches the declared budget |
| I-14 | `TakeoverWithinOneSecond` | A console takeover pauses the agent within 1 s |

---

## 8. Property tests

| Property | Generator |
|---|---|
| `SandboxPath` never escapes its root | Random path strings with `..`, absolute prefixes, unicode, NUL, symlink chains |
| The step DAG never contains a cycle | Random plans with random edge sets |
| A plan's step count never exceeds the hard limit | Random planner outputs |
| The budget ledger never goes negative | Random reserve/release sequences, including a leaked reservation |
| `settledTree` always returns within its cap | Random event sequences on the fake clock |
| The event stream is monotonic in `seq` | Random interleavings of the four writers |
| A run's trace fully reconstructs the step sequence | Random failure injection during a scenario |

---

## 9. Performance and quota probes

| Probe | Threshold |
|---|---|
| Tree snapshot (500 nodes) | ≤ 40 ms |
| Tree summariser | ≤ 20 ms |
| Settle detection (typical) | ≤ 300 ms |
| Action dispatch round trip | ≤ 250 ms |
| Room step transaction | ≤ 5 ms |
| Console first paint | ≤ 300 ms |
| Glass frame time | ≤ 8 ms at 120 Hz |
| Steady-state RAM | Within the `AGENTS.md` §2 budget |
| Steady-state storage | Within the `AGENTS.md` §2 budget |

`quotaProbe` writes a report to `sandbox/workspace/quota-report.json` and fails the build if a category exceeds its cap.

---

## 10. Definition of test done

A test is done when:

1. It has an ID from this document and a name matching the `given_when_then` convention.
2. It asserts a **named invariant**, and the name says which.
3. It fails without the fix it defends.
4. It is deterministic — no wall-clock dependency, no network, no sleep.
5. It lives in the module that owns the code.
6. If it covers a quota, it asserts the **exact** number.

**No test may be deleted to make a build green.** A test that no longer reflects the design is replaced, with the ID preserved and a note in the commit message.
