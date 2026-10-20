# ADR 0002 — `core:store` is an Android module, because Room is Android-only

- **Status:** Accepted
- **Date:** 2026-10-20
- **Deciders:** project owner
- **Relates to:** `AGENTS.md` §3, `ARCH.md` §2, `D4`

## Context

`AGENTS.md` §3 states that `core/*` is pure Kotlin/JVM with no `android.*`
imports, so the agent loop is testable on the JVM without a device. The
locked decision `D4` puts the hot run state in Room.

Room's runtime artifact is an Android library. `androidx.room:room-runtime`
wraps `android.database.sqlite` and cannot run on a plain JVM. The two
requirements collide at exactly one module: the store.

The options were:

1. Keep `core:store` pure by declaring only the persistence *port* there and
   putting the Room implementation somewhere else.
2. Make `core:store` an Android library and accept the exception.
3. Drop Room and use a pure-JVM SQL layer, or a hand-rolled file format.

Option 1 just relocates the module: the Room adapter still has to live in
some Android module, and the specification would gain a module that exists
only to hold an exception. Option 3 discards a locked decision to satisfy a
boundary rule, and re-implementing a SQL layer is a large amount of work that
buys nothing: the testability that matters is the *loop*, not the store.

## Decision

**`core:store` is an Android library module.** It is the single exception to
the `core/*` purity rule.

The boundary is preserved where it matters:

- The persistence **port** — `StateStore` — and every DTO it exchanges live
  in `core:model`, which stays pure.
- `core:agent` depends on the port, never on `core:store`. The Planner–Actor–
  Critic loop therefore remains a plain JVM module with a `FakeStateStore` in
  its test sources, which is what `TESTING.md` §5 requires.
- The Room entities, the DAO, and the migrations live in `core:store`, and
  they are the only place `androidx.room` appears.

The `dependencyGuard` rule is narrowed accordingly: `core/*` modules other
than `core:store` may not declare an `androidx.*` dependency at all, and
`core:store` is allowed exactly `androidx.room`, `androidx.datastore` and
`androidx.sqlite` — the last because SQLCipher's `SupportFactory` and Room
both sit on the `androidx.sqlite` support layer, and depending on it
transitively while importing it directly would be worse than declaring it.

The rule applies to **production** configurations only. A test dependency on
`androidx.test` does not put a library on a shipped classpath, so the guard
tracks the configuration each dependency was declared in and exempts the test
source sets.

## Alternatives considered

- **Keep `core:store` pure, move the Room adapter to a new `:platform:store`
  module.** Rejected: it adds a module that exists purely to hold an
  exception, and the port would still have to live somewhere. The boundary is
  better expressed by the dependency direction than by the module name.
- **Use SQLDelight, which has a real JVM artifact.** Rejected: it would
  replace Room under `D4` and change the migration story, for no benefit to
  the product. If the JVM-purity requirement ever becomes binding, this is
  the option to revisit, and it belongs in its own ADR.
- **Hand-rolled file persistence.** Rejected: it loses transactions, which the
  checkpointing rule in `ARCH.md` §11 depends on.

## Consequences

- `core:store` cannot be unit-tested on a plain JVM. Its tests are
  instrumentation tests, or Robolectric tests in the `core:store` module.
- `:core:agent:test` remains a plain JVM suite with no Robolectric, which is
  the property `TESTING.md` §1 depends on.
- The purity rule in `AGENTS.md` §3 now has a named exception. The wording is
  updated rather than left to be contradicted by the code.
- The quota effect is unchanged: the database and its WAL still live in the
  `state/` bucket, capped at 2,000 MiB.

## Follow-up

- [x] Update `AGENTS.md` §3 and `ARCH.md` §2 to name the exception.
- [x] Narrow the `dependencyGuard` core-boundary check to exempt
      `:core:store` and to allow only `androidx.room` and `androidx.datastore`
      there.
- [ ] `M2` — the Room schema, the DAO, and the migration harness.
