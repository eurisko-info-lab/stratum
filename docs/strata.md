# Strata

## The language for Stratum's second floor

Strata is a total, change-first, capability-safe language whose programs,
schemas, effects, laws, and compilation stages are all ordinary Stratum
artifacts.

It is not a general replacement for Scala, Rust, Lean, or Haskell. It is
designed specifically for Stratum: Canon, schemas, Grammar and Meta,
interpreters, compilers, CAS traversal, semantic repositories, changes,
materializers, foundations, governance, distributed protocols, studios, and
investigation agents.

The second floor begins at:

$$
F_{11} \vdash S_0
$$

where $S_0$ is the first Strata foundation. Successors are then built by
Strata itself:

$$
S_n \vdash S_{n+1}
$$

The existing Scala and Rust implementation remains as first-floor bootstrap and
independent reference.

## 1. What perfect means in this context

Strata is perfect for Stratum only if it optimizes Stratum's invariants, in
this order:

1. canonical identity
2. total and deterministic computation
3. changes as first-class languages
4. explicit effects and capabilities
5. schema-directed generic programming
6. laws and evidence as ordinary artifacts
7. content-addressed modularity
8. self-description and staged self-hosting
9. predictable compilation
10. machine-facing structure for agents

Traditional priorities such as unrestricted metaprogramming, implicit magic,
ambient interoperability, and object-oriented extensibility are subordinate.

## 2. Two-floor architecture

```text
First floor
----------------------------------------
Frozen Host0
Scala reference host
Rust independent host
F0 -> F1 -> ... -> F11

                 F11 |- S0

Second floor
----------------------------------------
Strata language
Strata compiler
Strata runtime libraries
Strata implementation of Stratum
S0 -> S1 -> ... -> S11
```

First floor job:

- prove legitimate origin
- provide Canon, SHA-256 identity, CAS, Meta0, Grammar0, capabilities,
  budgets, evidence, foundation construction, dual-host validation, clean-room
  reconstruction

Second floor job:

- provide typed schemas, typed judgments, typed effects, generated free change
  languages, migrations, compiler passes, repository operations, foundation
  machinery, agent investigation machinery

## 3. One language, three inseparable faces

For any declaration $A$:

$$
\boxed{A \quad Judgments(A) \quad \Delta A}
$$

- value language: what objects are
- judgment language: what may be concluded/computed
- change language: how values evolve

For every declared language $L$, Strata derives structural changes
$\Delta_s L$, which may be elaborated into semantic changes $\Delta_L$.

## 4. Surface syntax

Syntax should be small, regular, indentation-based, and grammar-defined. Names
are human-facing; canonical identity comes from stable identifiers and field
numbers.

## 5. Core type system

Strata should use a total dependent ML core:

- products, sums, records
- parametric polymorphism
- indexed families and GADTs
- dependent functions and pairs
- equality
- predicative universes
- explicit interfaces
- immutable values
- exhaustive pattern matching
- no null, exceptions, unchecked casts, ambient mutation, or unrestricted
  recursion

Typed references carry both digest and expected schema.

## 6. Totality

Ordinary definitions, relations, compiler passes, materializers, and verifiers
must terminate by:

- structural recursion
- well-founded recursion
- bounded computation
- explicit machines for ongoing behavior

No hidden nontermination.

## 7. Evaluation strategy

Strict, deterministic, call-by-value, immutable by default. Laziness,
nondeterminism, partiality, and infinite behavior are explicit.

## 8. Quantitative resources

Support multiplicities:

- erased
- once
- affine
- many

This enables safe handling of journals, handles, leases, staged builders, and
efficient implementations while preserving immutable semantics.

## 9. Schemas are first-class

Every data declaration emits a canonical schema value. Generic derivations
consume `Schema A` for codecs, references, fields, change languages, context
views, and generators.

## 10. Structural and semantic changes

For each type $A$, generated structural changes $\Delta A$ support apply,
diff, composition, and conflict detection with laws such as:

$$
apply(a, zero) = ok(a)
$$

$$
apply(a, diff(a,b)) = ok(b)
$$

Semantic change languages then elaborate into structural ones while preserving
domain laws.

## 11. Judgments and relations

Stratum requires first-class judgments with modes, evidence, measures, and
multi-rule execution. Deterministic modes can compile to functions; other modes
can compile to validators, searchers, explainers, and inverse queries.

## 12. Laws and evidence

Laws are ordinary declarations. Evidence levels are explicit:

- Declared
- Tested
- Generated
- CrossHost
- ModelChecked
- Derived
- Proved

Tests never masquerade as proofs.

## 13. Effects and capabilities

Pure language has no ambient IO. Interaction is through declared capabilities
with operational classifications:

- pure
- environmental
- reversible
- irreversible

Effect sets are explicit in types.

## 14. Interfaces without implicit fog

Ad hoc polymorphism uses named interfaces and named instances with explicit
identity after elaboration. Avoid inheritance, subtyping, overlapping/orphan
instances, and implicit search ambiguity.

## 15. Modules and content-addressed dependencies

Modules have human names plus canonical identity/version. Imports can require
expected interface digests. Elaboration records resolved immutable identities to
ensure reproducibility.

## 16. Source text is a projection

Authoritative form is typed canonical module. Source is an editable projection:

```text
surface source -> parse -> untyped syntax -> elaborate -> typed canonical module -> compile -> core
```

Documentation is structured attachments; formatting trivia is not semantic
identity unless explicitly chosen.

## 17. Metaprogramming

No unrestricted AST macros. Use:

- schema-directed derivation
- typed quotation and splicing
- language extensions as full language packages (grammar, elaborator, printer,
  changes, laws)

## 18. Compiler architecture

Compilation proceeds through explicit canonical IRs:

```text
Strata source
-> Surface AST
-> Resolved AST
-> Typed Core
-> Effect Core
-> A-normal IR
-> Strata Machine IR
-> Executable
```

Each stage is a judgment with preservation obligations.

## 19. Memory and performance

Semantic model remains persistent and immutable. Compiler may use uniqueness
proofs for safe destructive optimization. Primitive representations should be
specialized for canonical performance.

## 20. Concurrency and distribution

Foundational abstraction is deterministic state machines, not shared mutable
threads. Runtime executes commands and returns events; traces become verifiable
artifacts.

## 21. Agent support is structural

Declarations provide stable identities, schemas, references, field paths,
effects, laws, generated changes, documentation, and history. Agent edits are
modeled as first-class change artifacts.

## 22. Deliberate exclusions

Exclude features that undermine constitutional guarantees:

- unrestricted recursion
- null/exceptions/unchecked casts
- ambient IO/global state
- inheritance/subtyping/implicit conversions
- unrestricted macros and semantic plugins
- unordered canonical maps
- filename/classname semantic identity
- silent schema migration
- unspecified evaluation order
- undefined behavior

## 23. Bootstrap

Stage 0:

- define `languages/strata` grammar/meta/declaration artifacts
- write initial interpreter and type checker in existing Meta

Stage 1:

- write first Strata compiler in Strata
- compile it using Meta reference implementation to obtain $C_1$

Stage 2:

- self-compile compiler to obtain $C_2 = C_1(C_{source})$
- require fixed-point digest stability

$$
Digest(C_1) = Digest(C_2)
$$

Stage 3:

- add independent Strata Machine backend/interpreter
- require agreement across Meta0, Scala host, Rust host, and Strata Machine

## 24. Second-floor staircase

Second floor uses `S0 -> S11`:

- S0: Strata seed
- S1: canonical data
- S2: free changes
- S3: self-hosting compiler
- S4: Grammar and Meta rewritten
- S5: artifacts and CAS
- S6: semantic repository
- S7: tooling and studios
- S8: foundation and governance
- S9: ledger, sync, federation
- S10: retention and reconstruction
- S11: investigation agents

At S11, second floor reconstructs first-floor capability set and extends it
with agent substrate.

## 25. Rewrite strategy

Do not translate Scala file-by-file. Replace each module by contract, canonical
model, effects, laws, accepted traces, and new Strata implementation.

## 26. Criteria to retire Scala as active implementation

Scala can leave active implementation only when:

- Strata compiler self-compiles
- compiler fixed point is stable
- all canonical schemas are versioned
- persisted values use Strata codecs
- repository transitions use typed changes
- effects use declared capabilities
- first-floor transcripts replay
- Meta0 and Strata Machine agree
- independent hosts agree
- clean-room reconstruction succeeds
- $S_n$ derives $S_{n+1}$

## 27. Essential language equation

$$
\boxed{
Strata =
\text{total dependent functional core}
+ \text{canonical schemas}
+ \text{free structural changes}
+ \text{semantic change elaboration}
+ \text{typed judgments}
+ \text{algebraic capabilities}
+ \text{linear resources}
+ \text{laws and graded evidence}
+ \text{content-addressed modules}
+ \text{staged self-compilation}
}
$$

Compactly:

$$
\boxed{Strata = values + judgments + changes + evidence}
$$

## Final position

F11 is the completed first-floor landing. The second floor is a new structure,
not a continuation in implementation language terms.

Decisive bootstrap theorem:

$$
F_{11} \vdash S_0
$$

Decisive self-hosting theorem:

$$
Digest(compile_{C_1}(C)) = Digest(compile_{C_2}(C))
$$

Decisive replacement theorem (for accepted observations):

$$
Behavior_{Strata}(Stratum) = ConstitutedBehavior_{F11}(Stratum)
$$
