# Strata rebuild roadmap

## Rebuilding Stratum in Strata, in seventeen commits

This roadmap starts at `182b7bc` — the commit that completed the first-floor
landing and defined the second-floor architecture in
[docs/strata.md](../docs/strata.md). Nothing after that commit on `main` is
carried forward.

The plan is the staircase specified in
[docs/strata.md §24](../docs/strata.md), realized literally: **seventeen
commits, seventeen foundations, one layer per commit.**

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
foundations/S0..S16/   second-floor staircase
```

## The seventeen commits

| # | Layer | Constructs | Authority moves to Strata |
| --- | --- | --- | --- |
| 1 | S0 | Strata seed | — |
| 2 | S1 | canonical data | schemas, codecs |
| 3 | S2 | the dependent core | what a type may say |
| 4 | S3 | free changes | change languages |
| 5 | S4 | modules and names | the module graph |
| 6 | S5 | self-hosting compiler | the compiler itself |
| 7 | S6 | effects and multiplicities | what a program may do |
| 8 | S7 | the native boundary | what Strata may reach |
| 9 | S8 | grammar definitions rewritten | what a syntax is |
| 10 | S9 | Meta definitions rewritten | what a meaning is |
| 11 | S10 | artifacts and CAS | storage |
| 12 | S11 | semantic repository | history |
| 13 | S12 | tooling and studios | editor surface |
| 14 | S13 | foundation and governance | construction and acceptance |
| 15 | S14 | ledger, sync, federation | distribution |
| 16 | S15 | retention and reconstruction | durability |
| 17 | S16 | investigation agents | the agent substrate |

S1 was originally written here as "the total dependent core". It is two
layers, not one. S1 gives a declaration a type; S2 lets a type depend on a
value, which is a change to what can be said rather than to what is done with
what was already said — and it needs surface the seed does not have.

S7 was originally absent. Building S6 made the ceiling visible: Strata has no
primitives at all, so it cannot compare two characters or hash a byte, and
both of those are needed by the layer that rewrites the machines and the layer
that takes over storage. Reaching the host is a change to what can be said,
so it is a layer — and S6's capabilities are already the discipline for it.

S9 was originally part of S8, as "language definitions rewritten". Building S8
showed the two halves are different work. Grammar elaboration rearranges a
tree; Meta elaboration derives declarations nobody wrote — a predicate, a set
of tags, an accessor per field — with names built by concatenating onto the
type's name. That needs a crossing between name and text that S7's boundary
does not have. Bundling them would have held the smaller claim hostage to the
larger one.

S6 was originally part of S5. Self-hosting is a change to what does the
saying; effects and multiplicities are a change to what can be said. Bundling
them would have made the decisive commit decide two things at once.

S4 was originally absent. Building S2 made the gap visible: with no way for
one module to refer to another, a length-indexed vector had to declare `Nat`
and `add` a second time, which is not a library but the same declaration
written twice. Cross-module reference is a law family that can fail, so it is
a layer — and the vector is shipped as a file only once there, when it can
say what it depends on.

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

## Commit 3 — S2: the dependent core

**Constructs** $S_1 \vdash S_2$.

S1 gives a declaration a type. S2 lets a type depend on a value, which is a
change to what can be said rather than to what is done with what was already
said — so it begins with surface the seed does not have.

**Adds**

- surface for type parameters, dependent function types and equality
- `features/strata/depend.meta` — universes, indexed families, dependent
  application, equality and its eliminator
- normalisation, so that types equal up to computation are accepted
- a length-indexed family, written out in the checks — a family indexed by a
  value, and the operations a length index makes total. It is not shipped as
  a file, because a module that has to redeclare `Nat` is the same
  declaration written twice rather than a library; that arrives at S4
- `fixtures/strata/s2.transcript`, `foundations/S2/`

**Acceptance**

- a length-indexed family rejects the append whose index does not add up
- a proof of equality rewrites one side into the other
- a type that mentions a value is compared after computation, not before
- the universe hierarchy is checked: no type contains itself

**Exit condition** — a type may state what a value must satisfy, and the
checker decides it.

---

## Commit 4 — S3: free changes

**Constructs** $S_2 \vdash S_3$.

Change becomes derivable from schema, not hand-written.

**Adds**

- `strata/lib/change.strata` — $\Delta A$ generated from `Schema A`
- `apply`, `diff`, `compose`, conflict detection
- `strata/lib/law.strata` — laws with graded evidence
  (`Declared`, `Tested`, `Generated`, `CrossHost`, `ModelChecked`, `Derived`, `Proved`)
- semantic change elaboration into structural change
- `fixtures/strata/s3.transcript`, `foundations/S3/`

**Acceptance**

$$
apply(a, zero) = ok(a) \qquad apply(a, diff(a,b)) = ok(b)
$$

- generated-evidence law runs are reproducible from a recorded seed
- a semantic change elaborates and preserves its domain law

**Exit condition** — for any Strata type, its change language exists without
being written.

---

## Commit 5 — S4: modules and names

**Constructs** $S_3 \vdash S_4$.

Until here a module is closed: every name it uses, it declares. That is why
the length-indexed vector S2 checks has to declare `Nat` and `add` itself
instead of taking them from `strata/lib/nat.strata`. S4 lets one module refer
to another, which is a change to what can be said and brings a law family
with it — a name may now fail to resolve, a module may be missing, an import
may be ambiguous, a set of modules may contain a cycle, two modules may
disagree about the version of a third, and a set may hold two modules of one
name.

This is deliberately its own layer rather than part of the compiler. A
compiler that both resolved names across modules and compiled itself would be
doing two new things at once, and the seam between them is where a defect
hides.

**Adds**

- surface for importing: a module names what it depends on and what it exposes
- `features/strata/resolve.meta` — name resolution across a module set,
  producing a resolved module whose every name is qualified by the module that
  declares it
- cycle detection over the import graph, and a canonical topological order
- version agreement: a module set fixes one version per module name
- content-addressed module identity, so a module is named by what it says
  rather than by where it sits
- `strata/lib/prelude.strata` — `Nat`, `Bool` and their operations declared
  once, imported by the rest
- `strata/lib/vector.strata` — the length-indexed vector S2 checks inline,
  shipped as a file now that it can import what it needs
- `fixtures/strata/s4.transcript`, `foundations/S4/`

**Acceptance**

- the vector library imports `Nat` and `add` and declares neither, and the
  resulting foundation checks exactly what it checked before
- an unresolvable name is rejected, and says which module was searched
- an import cycle is rejected, and names the cycle
- two modules that require different versions of a third is rejected
- a module set holds one module per name, so two modules that call themselves
  the same thing are rejected rather than one silently winning
- resolution is confluent: the resolved form does not depend on the order the
  modules were given
- a module's identity is its content, so renaming a file changes nothing

The surface is `strata.v3.grammar` rather than an edit to `strata.grammar`. A
foundation records its sources by path and by content, so anything a
published foundation names is immutable: the tree is append-only for those
files, and a surface revision is a new file rather than a change to an old
one. The superseded revision's *generated* artifact is frozen under
`strata.vN.generated.*` and its consumers re-pointed, which is a rename and
not an edit — S2 and S3 rebuild to the digests they had. Library modules are
not versioned this way: a module carries the version it declares, and S2
checks its vector inline precisely so that only one `vector.strata` ever
exists.

**Exit condition** — a declaration is written once and used everywhere, and
the module graph is decided rather than assumed.

---

## Commit 6 — S5: self-hosting compiler

**Constructs** $S_4 \vdash S_5$. The decisive commit.

Strata is a language with no primitives: data, definitions, application,
`let` and `match`, and nothing else. A compiler for it can therefore be
written in it, and this is where that happens.

Names cannot be compared — there is no equality to compare them with — so the
compiler works on a resolved core in which a variable is an index and a
constructor is a tag. Resolving is the reader's job, not the compiler's.

**Adds**

- `strata/compiler/core.strata` — the resolved core: indices, tags, arities
- `strata/compiler/machine.strata` — the target: a flat instruction sequence
- `strata/compiler/compile.strata` — core → machine, written in Strata
- `features/strata/compile.meta` — the reader that resolves a checked module
  into core, the driver that runs the compiler, and the machine that runs its
  output
- `fixtures/strata/s5.transcript`, `foundations/S5/`

**Acceptance**

- $C_1$ = the compiler compiled by the Meta reference implementation
- $C_2 = C_1(C_{source})$ — the compiled compiler, run on its own source
- fixed point holds:

$$
Digest(C_1) = Digest(C_2)
$$

- running a compiled program gives what evaluating the source gives, for
  every fixture: the machine preserves meaning rather than resembling it
- the compiler compiles the subset it is written in, and that subset is
  decided rather than assumed

**Exit condition** — Strata compiles Strata, and the result is stable.

---

## Commit 7 — S6: effects and multiplicities

**Constructs** $S_5 \vdash S_6$.

S2 lets a type mention a value. S6 lets a type say what happens: which
capabilities a judgment needs, and how many times an argument may be used.
That is a change to what can be said, so it is its own layer rather than part
of the compiler. A compiler that both compiled itself and introduced a new
kind of type would be doing two new things at once, and the seam between them
is where a defect hides.

**Adds**

- surface for modes, capability requirements and multiplicities
  (`erased`, `once`, `affine`, `many`)
- `features/strata/effect.meta` — capability-typed effects, checked
- multiplicity checking: a value declared `once` is used once on every path
- the compiler's IR chain gains its effect stage, and erasure is checked to
  remove exactly what is `erased`

**Acceptance**

- a judgment that uses a capability it does not declare is rejected
- a value declared `once` and used twice is rejected, and says on which path
- a value declared `erased` does not appear in the compiled output
- erasure preserves meaning: the erased program and the unerased one agree on
  every fixture

**Exit condition** — a type says what a program may do, not only what it is.

---

## Commit 8 — S7: the native boundary

**Constructs** $S_6 \vdash S_7$.

Strata has no primitives. That is what made the compiler writable in it, and
it is also a ceiling: a language whose only operations are on its own
declarations cannot compare two characters, add two machine numbers, or hash
a byte. Every layer above this one needs at least one of those. The machines
cannot be rewritten in a language that cannot look at a character, and storage
cannot be Strata's while SHA-256 is not.

So the boundary is drawn, rather than the primitives being smuggled in. A
primitive is *declared*, not built in: it has a name, a type, and the
capability it needs, and it has no body. S6 already decides capabilities, so a
program that reaches the host without saying so is already rejected — the
boundary costs no new law family, only a new kind of declaration.

**Adds**

- surface for an abstract type and for a primitive declaration
- `strata/system/native.strata` — the primitive set, declared in Strata
- the evaluator performs a primitive by crossing values to the host and back
- `features/strata/native.meta` — the boundary: the declared set is exactly
  what the host provides, and the crossing is checked in both directions
- `fixtures/strata/s7.transcript`, `foundations/S7/`

**Acceptance**

- a primitive the host does not provide is rejected, and named
- a host primitive the module does not declare is not reachable
- using a primitive without declaring its capability is rejected — by S6's
  rule, unchanged
- a value that crosses to the host and back is the value that set out
- what the layers below decided, they still decide

**Exit condition** — Strata can reach the host, and only where it says so.

---

## Commit 9 — S8: grammar definitions rewritten

**Constructs** $S_7 \vdash S_8$.

A language in this repository is two things: a *definition* — the source that
says what its syntax and its judgments are — and a *machine* that runs that
definition over text. This layer moves half the first: what a syntax is. The
other half is S9, and the machines stay; both reasons are given below.

Grammar elaboration is a tree transformation. It takes a parsed grammar
source and emits the artifact the machine reads — no names invented, no
declarations derived, nothing looked up. That is pure structure, which is what
Strata is for, and it happens once per language rather than once per file.

It needs one thing beyond structure: a text literal has to be unescaped, and
until S7 Strata could not compare a character. It also needs Strata to stop
being wrong about text — a literal denoted its own spelling, quotes and
backslashes included, and had no escapes to spell a newline with. Neither is
an addition to what Strata can compute.

**Adds**

- `strata/system/grammar.strata` — grammar source to grammar artifact
- `features/strata/language.meta` — the reader and writer that carry a parse
  tree into Strata and the result back out
- a text literal in Strata that has escapes and denotes what it spells
- `fixtures/strata/s8.transcript`, `foundations/S8/`

**Acceptance**

- for every grammar in the tree — all nineteen, from the five-line one for
  plain text to Lean's hundred and thirty-five — the artifact the Strata
  elaborator produces is the artifact already committed, byte for byte
- therefore all existing language fixtures — Scala, Rust, JSON, YAML, TOML,
  Markdown, Lean, shell, transcript, and the adversarial sets — round-trip
  unchanged, because they are parsed with the same artifacts as before
- the Strata elaborator's matches are exhaustive over the syntax it accepts,
  so a source it would reject is one the grammar of grammars rejected first,
  before either elaborator saw it

**Authority moves** — grammar definitions. What a syntax *is* becomes
Strata's.

**Exit condition** — a syntax is defined in Strata, and the old grammar
elaborator only witnesses.

---

## Commit 10 — S9: Meta definitions rewritten

**Constructs** $S_8 \vdash S_9$.

The other half of what a language is. This was written as part of S8, and
building S8 showed it is not the same kind of work.

Grammar elaboration rearranges a tree. Meta elaboration *derives* things that
were not written down: a data declaration becomes a predicate, a set of
variant tags, and one accessor judgment per field, each with a name built by
concatenating onto the type's name. That is a code generator, and it needs
Strata to take a name apart and put one together — symbol and text are not
the same type, and S7's boundary provides no crossing between them.

So this layer draws that crossing and then uses it. Splitting it from S8 keeps
the smaller claim — nineteen grammars, byte for byte — from being held up by
the larger one.

**Adds**

- two primitives at the S7 boundary: a name as text, and a text as a name
- `strata/system/meta.strata` — Meta source to Meta artifact
- the reader and writer for Meta's syntax tree
- `fixtures/strata/s9.transcript`, `foundations/S9/`

**Acceptance**

- for every Meta program in the tree, the artifact the Strata elaborator
  produces is the artifact already committed, byte for byte
- including the self-description: elaborating `languages/meta/meta.meta`
  reaches the same fixpoint it reaches today
- every name the generator invents — predicates, tags, accessors — is the
  name the Meta elaborator invents

**Authority moves** — Meta definitions. What a meaning *is* becomes Strata's.

**What does not move, and why.** Grammar0 and Meta0 keep running the
definitions. Rewriting the machines themselves is not blocked by expressive
power — S7 gave Strata the boundary it needs to look at a character — but by
speed. The bootstrap compiles a 2.7 kB compiler and needs a budget of four
hundred million steps; the language corpus is 273 files and some 700 kB, and
parsing is heavier per byte than the tree walk the bootstrap does. A Strata
parser interpreted by Meta0 would take hours per gate run, which is not a
gate. Moving the machines needs a fast path, and the plan does not have one
yet.

**Exit condition** — a language is defined in Strata, and the old elaborators
only witness.

---

## Commit 11 — S10: artifacts and CAS

**Constructs** $S_8 \vdash S_{10}$.

**Adds**

- `strata/system/artifact.strata`, `strata/system/cas.strata`
- SHA-256 identity, canonical encoding, content-addressed store, typed
  references carrying digest **and** expected schema
- `fixtures/strata/s10.transcript`, `foundations/S10/`

**Acceptance**

- existing `foundations/F*/closure/*.canon` are readable and digest-identical
  through the Strata store
- a typed reference with a mismatched schema is rejected, not coerced

**Exit condition** — storage is Strata's, and it is bit-compatible with the
first floor.

---

## Commit 12 — S11: semantic repository

**Constructs** $S_{10} \vdash S_{11}$.

**Adds**

- `strata/system/repository.strata` — init, record, status, verify, branch,
  checkout, materializers
- transitions expressed as typed changes from S2, not diffs over bytes
- `fixtures/strata/s11.transcript`, `foundations/S11/`

**Acceptance**

- every scenario in `test-repo-scala/StratumRepoSuite.scala` passes against the
  Strata repository
- chains recorded by `repo-scala` verify under Strata and vice versa
- tampered patches are still rejected

**Authority moves** — history. `repo-scala` becomes reference.

**Exit condition** — repository state advances only through typed changes.

---

## Commit 13 — S12: tooling and studios

**Constructs** $S_{11} \vdash S_{12}$.

**Adds**

- `strata/system/studio.strata` — profile-derived editor surface
- LSP service derived from language declarations, replacing `host-scala/lsp`
- `fixtures/strata/s12.transcript`, `foundations/S12/`

**Acceptance**

- `tools/lsp.sh` scenarios pass against the Strata service
- studio surface is derived from a profile, never hand-listed

**Exit condition** — the editor is a projection of the language declarations.

---

## Commit 14 — S13: foundation and governance

**Constructs** $S_{12} \vdash S_{13}$.

**Adds**

- `strata/system/foundation.strata` — construction, closure, attestation
- `strata/system/governance.strata` — proposal, acceptance, sponsorship
- `fixtures/strata/s13.transcript`, `foundations/S13/`

**Acceptance**

- Strata reconstructs `foundations/F0` through `F11` with identical digests
- `runbooks/attest-a-foundation.transcript` and
  `runbooks/rebuild-a-foundation.transcript` replay under Strata

**Exit condition** — the staircase itself is built by Strata.

---

## Commit 15 — S14: ledger, sync, federation

**Constructs** $S_{13} \vdash S_{14}$.

**Adds**

- `strata/system/ledger.strata`, `sync.strata`, `federation.strata`
- deterministic state machines: commands in, events out, traces as artifacts
- `fixtures/strata/s14.transcript`, `foundations/S14/`

**Acceptance**

- all `fixtures/ledger`, `fixtures/sync`, `fixtures/federation` transcripts pass
- replaying a recorded trace reproduces the terminal state digest exactly
- a divergent peer is detected rather than silently merged

**Exit condition** — distribution is deterministic and replayable.

---

## Commit 16 — S15: retention and reconstruction

**Constructs** $S_{14} \vdash S_{15}$.

**Adds**

- `strata/system/retention.strata` — retention classes, expiry, reconstruction
- clean-room reconstruction driven by Strata
- `fixtures/strata/s15.transcript`, `foundations/S15/`

**Acceptance**

- `tools/cleanroom.sh` reconstructs the whole system using the Strata
  implementation from frozen origin plus recorded changes
- expired content is provably unreachable, not merely hidden

**Exit condition** — the system can be rebuilt from origin by Strata alone.

---

## Commit 17 — S16: investigation agents

**Constructs** $S_{15} \vdash S_{16}$, and closes the floor.

**Adds**

- `strata/system/agent.strata` — investigation over declarations: stable
  identities, schemas, field paths, effects, laws, changes, history
- agent edits as first-class change artifacts with capability limits and budgets
- `fixtures/strata/s16.transcript`, `foundations/S16/`

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

## What the plan does not yet provide

Strata runs by being interpreted: Meta0 walks its terms, and S5's machine is
itself interpreted by Meta0. That is fast enough for everything the floor has
needed so far — a foundation builds in seconds — and it is not fast enough to
take over work that is currently done by compiled Scala on the whole
repository at once. S8 records where that bites first.

The endgame in §24 is that Stratum runs on Strata and the first floor becomes
reference. That needs an execution path that is not interpretation: Strata
compiled to something the host runs directly, rather than to a machine another
interpreter walks. Nothing in these seventeen commits provides one.

This is written down rather than discovered later. Whether it becomes a
seventeenth layer or a change to S5's target is a decision that should be
taken with a measurement in hand, not now.

## Definition of done

Fully operational means all three hold simultaneously:

$$
F_{11} \vdash S_0, \quad S_n \vdash S_{n+1} \text{ for } n < 15
$$

$$
Digest(compile_{C_1}(C)) = Digest(compile_{C_2}(C))
$$

$$
Behavior_{Strata}(Stratum) = ConstitutedBehavior_{F_{11}}(Stratum)
$$

Stratum is then a system that describes, changes, verifies, and rebuilds itself
in its own language.
