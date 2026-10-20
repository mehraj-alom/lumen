# ADR 0001 — Record the architecture decisions and the dependency approval gate

- **Status:** Accepted
- **Date:** 2026-10-20
- **Deciders:** project owner
- **Relates to:** `AGENTS.md` §1, §4, §6.1

## Context

Phase 3 produced a complete specification suite whose decisions were reached in
a single decision gate. Those decisions now govern the implementation, but
nothing in the repository made them mechanically enforceable, and the clean-room
boundary in `AGENTS.md` §5 was a prose rule that any future contributor could
quietly ignore.

Two failure modes were concrete:

1. A contributor adds a dependency, a permission, or a quota change without
   asking, and the request is discovered after the code is written.
2. A `core/*` module acquires an `androidx.*` dependency, and the module
   boundary that makes the agent loop testable on the JVM erodes silently.

## Decision

1. **Every locked decision gets an ADR.** A change to a decision in `AGENTS.md`
   §1, a quota in §2, a permission set, a dependency set, or a public contract
   is not landed until an ADR is written and accepted.

2. **The dependency ledger is enforced by a build task.** `:dependencyGuard`
   fails the build when:
   - a `core/*` module declares an Android or cross-module dependency,
   - a declared dependency is absent from `gradle/approved-dependencies.txt`,
   - a declared dependency matches `gradle/pending-dependencies.txt`, which
     lists the dependencies awaiting explicit user approval.

3. **The specification ledger is enforced too.** `:dependencyLedger` fails the
   build when a declared dependency is not documented in `TECHSTACK.md` §10.

4. **The clean-room boundary is a review duty backed by a diff rule.** The
   reference analysis is frozen. No file under the reference path may be added
   to the repository, and no reference identifier may appear in source.

## Alternatives considered

- **Prose-only rules.** Rejected: `AGENTS.md` was already prose, and the two
  failure modes above are exactly the kind that prose does not stop.
- **A separate Gradle plugin project (`buildSrc`) for the guard.** Rejected
  for M0: it adds a compilation unit to the build before any product code
  exists, and the same rule can be expressed as a root-project task that reads
  the build files directly. Revisit when the guard needs to inspect resolved
  configurations rather than declared coordinates.
- **Allowing `core/*` to depend on `androidx.*` freely.** Rejected: the entire
  reason the loop is testable without a device is that `core/*` is pure.

## Consequences

- A contributor cannot add a dependency without a build failure, so the
  approval conversation happens before the code, not after.
- The guard is a static scan of build files. A dependency introduced through a
  convention plugin or a transitive dependency would not be caught by the
  coordinate scan, only by `:dependencyLedger`. Both are required.
- `gradle/pending-dependencies.txt` is the single place where the approval
  queue lives. Removing a line from it is the act of approval, and it must be
  recorded in `TECHSTACK.md` §10.

## Follow-up

- [x] `M0` — the guard, the ledger check, the approved and pending lists.
- [ ] `M14` — remove `org.llama` from the pending list once the native
      dependency is approved.
- [ ] `M2` — remove `net.zetetic` from the pending list once SQLCipher is
      approved.
