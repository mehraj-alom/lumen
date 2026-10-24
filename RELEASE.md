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

**A release is not a release until the device suite has run on a physical device with the accessibility service enabled.** The instrumentation tests for the accessibility service, the overlay, the shader and the indicator all need it.

**Five controls this checklist takes for granted are not wired yet**, and each is an open item in `TASK.md` §M15 with the evidence beside it: the thermal and battery governors have no caller, so nothing throttles a hot phone or stops a run at 19 %, the settings screen is drawn but unreachable and its spectating switch reaches neither process, the crash reporter is never installed as the uncaught-exception handler, the console's step counter never leaves zero during a run, and the cost meter has no source. Every one of them fails closed — no quota is exceeded, no content leaves the device — so they gate the *promises* in `OPERATIONS.md`, not the safety of the build. A release note has to say so rather than describe a control the user cannot find.

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
