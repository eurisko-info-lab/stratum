# Strata rebuild roadmap

## Rebuilding Stratum in Strata, in twelve commits

This roadmap starts at `182b7bc` — the commit that completed the first-floor
landing and defined the second-floor architecture in
[docs/strata.md](../docs/strata.md). Nothing after that commit on `main` is
carried forward.

The plan is the staircase already specified in
[docs/strata.md §24](../docs/strata.md), realized literally: **twelve commits,
twelve foundations, one layer per commit.**

$$
F_{11} \vdash S_0 \qquad S_n \vdash S_{n+1}
$$

At the end, Stratum is implemented in Strata, and the Scala and Rust hosts
remain only as frozen origin and independent cross-checks.

## Ground rules

These hold at **every** commit, not at the end. There is no "work in progress"
state on this branch.

1. The tree builds: `sbt -batch test repoTool/test` is green.
2. Every transcript in `fixtures/` replays byte-identically.
3. The Scala and Rust hosts agree: `tools/parity.sh` is green.
4. The first-floor staircase still reconstructs: `tools/staircase.sh` is green.
5. Clean-room reconstruction still succeeds: `tools/cleanroom.sh` is green.
6. Each commit adds exactly one second-floor foundation under `foundations/S<n>/`,
   with its own `application.canon`, `build.canon`, and `closure/`.
7. No first-floor component is deleted before the layer that replaces it is
   accepted. Replacement is a demotion from authoritative to reference, never a
   gap.

### Anti-pattern, explicitly rejected

The discarded work grew by emitting structurally identical modules from a
template and widening transcripts to match. That is volume, not construction.

**A declaration may only be added if it introduces at least one of:**

- a new law family that can fail,
- a new judgment mode,
- a new effect or capability,
- a new observable command surface.

Templated variants of an existing contract are not deliverables.

## Layout

```text
languages/strata/      surface grammar, language declaration, materializer
features/strata/       Meta reference implementation (elaborator, checker, evaluator)
strata/lib/            Strata standard library, written in Strata
strata/compiler/       Strata compiler, written in Strata
strata/system/         Stratum itself, written in Strata
fixtures/strata/       acceptance transcripts
foundations/S0..S11/   second-floor staircase
```

## The twelve commits

| # | Layer | Constructs | Authority moves to Strata |
| --- | --- | --- | --- |
| 1 | S0 | Strata seed | — |
| 2 | S1 | canonical data | schemas, codecs |
| 3 | S2 | free changes | change languages |
| 4 | S3 | self-hosting compiler | the compiler itself |
| 5 | S4 | Grammar and Meta rewritten | language definitions |
| 6 | S5 | artifacts and CAS | storage |
| 7 | S6 | semantic repository | history |
| 8 | S7 | tooling and studios | editor surface |
| 9 | S8 | foundation and governance | construction and acceptance |
| 10 | S9 | ledger, sync, federation | distribution |
| 11 | S10 | retention and reconstruction | durability |
| 12 | S11 | investigation agents | the agent substrate |

---

## Commit 1 — S0: Strata seed

**Constructs** $F_{11} \vdash S_0$.

Strata becomes a language the existing system can read, print, store, and
reconstruct — before it can compute anything.

**Adds**

- `languages/strata/` — grammar, language declaration, `.strata` materializer id
- `features/strata/elaborate.meta` — surface → untyped syntax
- `features/strata/eval.meta` — Meta0 evaluator for the core forms
- `fixtures/languages/adversarial/strata/` — hostile round-trip cases
- `fixtures/strata/s0.transcript`
- `foundations/S0/`

**Acceptance**

- parse → print is byte-identical for every fixture, on both hosts
- `stratum repo record` / `checkout` preserves `.strata` through the
  materializer, not as an opaque blob
- a Strata expression evaluates under Meta0 with a stable digest

**Exit condition** — a `.strata` file is a first-class Stratum artifact with
canonical identity.

---

## Commit 2 — S1: canonical data

**Constructs** $S_0 \vdash S_1$.

The total dependent core, and the schema every declaration emits.

**Adds**

- `features/strata/check.meta` — type checker: products, sums, records,
  indexed families, dependent functions, equality, universes
- `features/strata/total.meta` — structural and well-founded termination
- `strata/lib/schema.strata` — `Schema A` as an ordinary value
- codec derivation: `encode`/`decode` from `Schema A`, digest-stable
- `fixtures/strata/s1.transcript`, `foundations/S1/`

**Acceptance**

- ill-typed and non-terminating fixtures are rejected with stable diagnostics
- `decode(encode(a)) = ok(a)` for every derived codec
- schema digests are stable across both hosts

**Exit condition** — every Strata declaration has a typed canonical form and a
canonical schema.

---

## Commit 3 — S2: free changes

**Constructs** $S_1 \vdash S_2$.

Change becomes derivable from schema, not hand-written.

**Adds**

- `strata/lib/change.strata` — $\Delta A$ generated from `Schema A`
- `apply`, `diff`, `compose`, conflict detection
- `strata/lib/law.strata` — laws with graded evidence
  (`Declared`, `Tested`, `Generated`, `CrossHost`, `ModelChecked`, `Derived`, `Proved`)
- semantic change elaboration into structural change
- `fixtures/strata/s2.transcript`, `foundations/S2/`

**Acceptance**

$$
apply(a, zero) = ok(a) \qquad apply(a, diff(a,b)) = ok(b)
$$

- generated-evidence law runs are reproducible from a recorded seed
- a semantic change elaborates and preserves its domain law

**Exit condition** — for any Strata type, its change language exists without
being written.

---

## Commit 4 — S3: self-hosting compiler

**Constructs** $S_2 \vdash S_3$. The decisive commit.

**Adds**

- `strata/compiler/` — the Strata compiler, written in Strata, with explicit
  IRs: Surface → Resolved → Typed Core → Effect Core → A-normal → Machine IR
- judgments with modes, capability-typed effects, linear multiplicities
  (`erased`, `once`, `affine`, `many`)
- `fixtures/strata/s3.transcript`, `foundations/S3/`

**Acceptance**

- $C_1$ = compiler compiled by the Meta reference implementation
- $C_2 = C_1(C_{source})$
- fixed point holds:

$$
Digest(C_1) = Digest(C_2)
$$

- each IR transition carries its preservation obligation as a checked law

**Exit condition** — Strata compiles Strata, and the result is stable.

---

## Commit 5 — S4: Grammar and Meta rewritten

**Constructs** $S_3 \vdash S_4$.

**Adds**

- `strata/system/grammar.strata`, `strata/system/meta.strata`
- every `languages/*` declaration re-derived from the Strata definitions
- `fixtures/strata/s4.transcript`, `foundations/S4/`

**Acceptance**

- all existing language fixtures — Scala, Rust, JSON, YAML, TOML, Markdown,
  Lean, shell, transcript, and the adversarial sets — round-trip unchanged
- Grammar0/Meta0 and the Strata implementations agree on every fixture

**Authority moves** — language definitions. Grammar0 and Meta0 become frozen
reference.

**Exit condition** — languages are defined in Strata; the old machines only
witness.

---

## Commit 6 — S5: artifacts and CAS

**Constructs** $S_4 \vdash S_5$.

**Adds**

- `strata/system/artifact.strata`, `strata/system/cas.strata`
- SHA-256 identity, canonical encoding, content-addressed store, typed
  references carrying digest **and** expected schema
- `fixtures/strata/s5.transcript`, `foundations/S5/`

**Acceptance**

- existing `foundations/F*/closure/*.canon` are readable and digest-identical
  through the Strata store
- a typed reference with a mismatched schema is rejected, not coerced

**Exit condition** — storage is Strata's, and it is bit-compatible with the
first floor.

---

## Commit 7 — S6: semantic repository

**Constructs** $S_5 \vdash S_6$.

**Adds**

- `strata/system/repository.strata` — init, record, status, verify, branch,
  checkout, materializers
- transitions expressed as typed changes from S2, not diffs over bytes
- `fixtures/strata/s6.transcript`, `foundations/S6/`

**Acceptance**

- every scenario in `test-repo-scala/StratumRepoSuite.scala` passes against the
  Strata repository
- chains recorded by `repo-scala` verify under Strata and vice versa
- tampered patches are still rejected

**Authority moves** — history. `repo-scala` becomes reference.

**Exit condition** — repository state advances only through typed changes.

---

## Commit 8 — S7: tooling and studios

**Constructs** $S_6 \vdash S_7$.

**Adds**

- `strata/system/studio.strata` — profile-derived editor surface
- LSP service derived from language declarations, replacing `host-scala/lsp`
- `fixtures/strata/s7.transcript`, `foundations/S7/`

**Acceptance**

- `tools/lsp.sh` scenarios pass against the Strata service
- studio surface is derived from a profile, never hand-listed

**Exit condition** — the editor is a projection of the language declarations.

---

## Commit 9 — S8: foundation and governance

**Constructs** $S_7 \vdash S_8$.

**Adds**

- `strata/system/foundation.strata` — construction, closure, attestation
- `strata/system/governance.strata` — proposal, acceptance, sponsorship
- `fixtures/strata/s8.transcript`, `foundations/S8/`

**Acceptance**

- Strata reconstructs `foundations/F0` through `F11` with identical digests
- `runbooks/attest-a-foundation.transcript` and
  `runbooks/rebuild-a-foundation.transcript` replay under Strata

**Exit condition** — the staircase itself is built by Strata.

---

## Commit 10 — S9: ledger, sync, federation

**Constructs** $S_8 \vdash S_9$.

**Adds**

- `strata/system/ledger.strata`, `sync.strata`, `federation.strata`
- deterministic state machines: commands in, events out, traces as artifacts
- `fixtures/strata/s9.transcript`, `foundations/S9/`

**Acceptance**

- all `fixtures/ledger`, `fixtures/sync`, `fixtures/federation` transcripts pass
- replaying a recorded trace reproduces the terminal state digest exactly
- a divergent peer is detected rather than silently merged

**Exit condition** — distribution is deterministic and replayable.

---

## Commit 11 — S10: retention and reconstruction

**Constructs** $S_9 \vdash S_{10}$.

**Adds**

- `strata/system/retention.strata` — retention classes, expiry, reconstruction
- clean-room reconstruction driven by Strata
- `fixtures/strata/s10.transcript`, `foundations/S10/`

**Acceptance**

- `tools/cleanroom.sh` reconstructs the whole system using the Strata
  implementation from frozen origin plus recorded changes
- expired content is provably unreachable, not merely hidden

**Exit condition** — the system can be rebuilt from origin by Strata alone.

---

## Commit 12 — S11: investigation agents

**Constructs** $S_{10} \vdash S_{11}$, and closes the floor.

**Adds**

- `strata/system/agent.strata` — investigation over declarations: stable
  identities, schemas, field paths, effects, laws, changes, history
- agent edits as first-class change artifacts with capability limits and budgets
- `fixtures/strata/s11.transcript`, `foundations/S11/`

**Acceptance — the retirement criteria from [docs/strata.md §26](../docs/strata.md)**

1. the Strata compiler self-compiles
2. the compiler fixed point is stable
3. all canonical schemas are versioned
4. persisted values use Strata codecs
5. repository transitions use typed changes
6. effects use declared capabilities
7. first-floor transcripts replay
8. Meta0 and the Strata Machine agree
9. independent hosts agree
10. clean-room reconstruction succeeds
11. $S_n$ derives $S_{n+1}$

**Exit condition — the replacement theorem, for accepted observations:**

$$
Behavior_{Strata}(Stratum) = ConstitutedBehavior_{F_{11}}(Stratum)
$$

When this holds, Scala leaves active implementation and remains as independent
reference.

## Definition of done

Fully operational means all twelve hold simultaneously:

$$
F_{11} \vdash S_0, \quad S_n \vdash S_{n+1} \text{ for } n < 11
$$

$$
Digest(compile_{C_1}(C)) = Digest(compile_{C_2}(C))
$$

$$
Behavior_{Strata}(Stratum) = ConstitutedBehavior_{F_{11}}(Stratum)
$$

Stratum is then a system that describes, changes, verifies, and rebuilds itself
in its own language.
