# tools/

Developer scripts. None of them ship in the APK.

| Script | Milestone | Purpose |
|---|---|---|
| `generate-modules.sh` | M0 | Regenerates the module skeleton. Run it from `android/`. It is idempotent: it rewrites the `build.gradle.kts` and the `ModuleInfo` files of the modules it owns. |
| `quota-probe` | M3 | Reports the live RAM and storage footprint against the budgets in `AGENTS.md` §2. Writes `sandbox/workspace/quota-report.json`. |
| `tree-dumper` | M4 | Dumps a synthetic or captured accessibility tree as JSON, for offline analysis of the grounding pipeline. |
| `bench` | M3, M4 | JMH harnesses for the planner, the tree parse, and the tree summariser. |

## Rules

- A script must not require a device connection.
- A script must not write outside the project directory.
- A script that mutates the build must be idempotent and must be listed in the milestone that owns it.
