# tools/

Developer scripts. None of them ship in the APK.

| Script | Milestone | Purpose |
|---|---|---|
| `generate-modules.sh` | M0 | Regenerates the module skeleton. Run it from `android/`. It is idempotent: it rewrites the `build.gradle.kts` and the `ModuleInfo` files of the modules it owns. |
| `quota-probe` | M3 | Reports the live RAM and storage footprint against the budgets in `AGENTS.md` §2. Writes `sandbox/workspace/quota-report.json`. |
| `tree-dumper` | M4 | Dumps a synthetic or captured accessibility tree as JSON, for offline analysis of the grounding pipeline. |
| `bench` | M3, M4 | JMH harnesses for the planner, the tree parse, and the tree summariser. |

## Local toolchain

The build machine needs a JDK 17 and an Android SDK with platform 35 and
build-tools 35.0.0. `android/local.properties` points at the SDK and is not
committed.

| Tool | Location on the reference machine |
|---|---|
| JDK 17 | `/home/opticpulse/opt/jdk-17.0.20.1+1` |
| Android SDK | `/home/opticpulse/opt/android-sdk` (platforms 35 + 36, build-tools 35 + 36, NDK 27) |
| Node | 22.x, for `console-web` |

A JDK 21 is also installed at `/home/opticpulse/opt/jdk-21.0.12.1+1`. It is
used by the REA tooling, not by the Gradle build.

**Memory.** The Gradle build is configured for a 1.5 GB heap in
`gradle.properties`. It does not fit comfortably on a machine with less than
about 4 GB free. Run it in CI or on a larger machine.

## Rules

- A script must not require a device connection.
- A script must not write outside the project directory.
- A script that mutates the build must be idempotent and must be listed in the milestone that owns it.
