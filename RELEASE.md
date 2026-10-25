# RELEASE.md — Shipping LUMEN

**Status:** Draft for review · **Codename:** LUMEN

The checklist for a release. Every item is a command, a file, or a check that a person can do. Nothing here is "make sure it works".

---

## 1. The gates

```bash
# Everything CI runs, in the one invocation ci.yml uses (from `android/`)
./gradlew lint ktlintCheck detekt test dependencyGuard dependencyLedger :app:assembleDebug --continue

# The pure-JVM suites that carry the loop, the quota engine and the policy
./gradlew :core:model:test :core:quota:test :core:policy:test \
          :core:agent:test :core:sandbox:test :core:grounding:test \
          :core:brain:test :core:action:test

# The device suites. The accessibility service must be enabled.
./gradlew connectedDebugAndroidTest

# The builds
./gradlew :app:assembleRelease :agent:assembleRelease
cd console-web && npm ci && npm run build && npm run lint
```

Every task name above is registered: the two dependency guards live on the root project (`:dependencyGuard`, `:dependencyLedger`), the test tasks are per-module (`:core:quota:test` — `:core:quota:quotaTest` and `:core:agent:loopRecoveryTest` are suite *titles* in `TESTING.md`, not tasks), and the web console is an npm project with no Gradle task at all.

**A release is not a release until the device suite has run on a physical device with the accessibility service enabled.** The instrumentation tests for the accessibility service, the overlay, the shader and the indicator all need it. **And a green device job is not a run device suite:** until the batch that added `testInstrumentationRunner` to `:core:store` and `:platform:accessibility`, the connected task in those modules installed an APK that nothing could start and passed on zero tests. Run `40177522176` is the first run where they executed, and it failed five of the store's seven on a malformed fixture id — a 27-character ULID where `RunId` requires 26 — which is what six months of unstarted suites had been hiding. The next run (`40178634218`) left one failure, and that one was real: the plaintext probe could not open a SQLCipher database because nothing in this tree ever called `System.loadLibrary("sqlcipher")`, so the encrypted state store had never worked on any device. Both are fixed, and the store suite is the proof of why the count matters. Read the test count in the report, not the colour.

**The console reaches the runtime, the run reports its steps, and four controls still do not work.** The shell is wired: the activity hosts the disclosure, the brain setup and the console; it starts and binds the `:agent` process; the goal carries the run's brain configuration and limits; the notification's Stop aborts the live run; the RAM, storage and step meters are read over `getBudget` when the state changes; and the glass indicator lights through the accessibility bridge for the duration of a run and goes out when it ends. What is still open, each with its item in `TASK.md` §M15: the thermal and battery governors have no caller, so nothing throttles a hot phone or stops a run at 19 %; `SettingsScreen` is drawn but unreachable, so the indicator mode and the spectating setting cannot be changed from it (screen-text consent is taken on the setup screen and travels with each goal instead); the crash reporter is never installed as the uncaught-exception handler; and the cost meter has no source, since nothing adds up the tokens a reply carried. The step counter, the intent line and the reason line do move: the loop names each step it chooses before it dispatches it. **What the console still cannot show is the plan graph** — the state stream carries a position in the plan rather than its shape, and the per-step result fields of an event have no writer.

**One surface an approval can appear on is missing.** The bridge carries no card methods, so the glass approval card cannot be raised from the runtime: a run that reaches a gate simply waits, and the decision is made on the console screen or not at all. The safety property is intact — `PolicyEngine` denies by default and the loop will not dispatch an unapproved irreversible step (`I4`) — but a user who backgrounds the app during a run sees a motionless indicator and no card. Every one of the gaps above fails closed — no quota is exceeded, no content leaves the device — so they gate the *promises* in `OPERATIONS.md`, not the safety of the build. A release note has to say so rather than describe a control the user cannot find.

**Where the credential lives.** The endpoint, the model id and the API key are typed on the setup screen and stored in the console process's own private `SharedPreferences` (`lumen_settings`, `MODE_PRIVATE`), readable only by this app. They are sent with each goal over the IPC inside the process, and from there to exactly one destination: the endpoint the user typed, as the bearer token of the completion call. Nothing logs them, nothing writes them to the sandbox, and the web console never sees them. A run whose configuration is incomplete fails with `brain_unavailable` before it dispatches anything.

---

## 2. The native flavour

The default build ships no native code and no model weights, and the agent is fully functional without them.

```bash
# With the local brain
./gradlew :app:assembleLocalInferenceRelease
```

This flavour needs the llama.cpp sources vendored at `platform/inference/src/main/cpp/llama.cpp`. The build fails with a clear message when they are absent.

**A release that ships the local brain must also verify** that the model is **not** bundled: the weights are user-installed, and the app never downloads one.

---

## 3. The disclosure

The Play Store review asks for exactly this, and the app must show it before the accessibility service is enabled.

- The in-app screen: `app/src/main/kotlin/dev/lumen/app/ui/DisclosureScreen.kt`.
- The service's own description: `platform/accessibility/src/main/res/values/strings.xml`.
- The store listing must say the same thing, in the same words. Do not write a different version for the store.

**What the listing must state:**

1. The service is used to read the screen and to tap, swipe and type on the user's behalf.
2. The app is an automation tool the user operates, not an agent that acts on its own.
3. There is a visible on-screen indicator whenever it is running.
4. The user can take over at any moment, and can revoke the service in Settings.
5. What is collected, and that screen content does not leave the device unless spectating is on.

---

## 4. The privacy claims

Every one of these is enforced in code, and each has a test:

| Claim | Enforced by | Test |
|---|---|---|
| Screen content does not leave the device unless spectating is on | `ContentEgressGuard` | the egress suite in `core:brain` |
| The observation store is encrypted at rest | `DatabaseKeyProvider`, SQLCipher | the store's device tests |
| The agent never downloads a model | `LocalModelRegistry`, the consent step | manual |
| Crash reporting is off by default and redacted | `CrashReporter` | `CrashReporterTest` |
| The workspace is never auto-deleted | `StorageQuota` | `Q-STO-09` |
| The pair token is never in a log line | the redaction set in `LumenLog` | the logging suite |

---

## 5. Before you ship

- [ ] The full gate in §1 passes on CI.
- [ ] The device suite passes on a physical device with the accessibility service enabled.
- [ ] The thermal soak: `./android/tools/soak.sh 30` — the peak temperature, the lowest battery and the peak PSS are inside the budgets in `AGENTS.md` §2.
- [ ] The quota probe: `./android/tools/quota-probe.sh` — every category inside its budget.
- [ ] The hundred-step acceptance scenario passes (`L-100-*`).
- [ ] The soak test passes (`SoakTest`).
- [ ] The disclosure in §3 is in the app **and** in the store listing, in the same words.
- [ ] The store listing's data-safety form matches the privacy claims in §4.
- [ ] The `EXPECTED_PERMISSIONS` golden file still matches the merged manifest. A permission that appeared by accident fails the build.
- [ ] The release notes state the steady-state RAM and storage footprint.
- [ ] `OPERATIONS.md` matches the shipped behaviour. It is the document the user reads.

---

## 6. The version

The codename is a placeholder. When it is replaced, these all change together:

- The application id, in `app/build.gradle.kts`.
- The agent's application id, in `agent/build.gradle.kts`.
- The database name, in `LumenDatabase.DATABASE_NAME`.
- The notification channel id, in `AgentRuntimeService`.
- The file paths under the sandbox, which are derived from the bucket names rather than the codename.
- The web console's title and its websocket path, in `console-web/`.

The codename does **not** appear in the protocol, so renaming it does not break a pairing.
