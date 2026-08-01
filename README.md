# Stratum

[![staircase](https://github.com/eurisko-info-lab/stratum/actions/workflows/staircase.yml/badge.svg)](https://github.com/eurisko-info-lab/stratum/actions/workflows/staircase.yml)

**Stratum builds a programming system out of itself, one provable step at a
time, and never trusts a step it cannot re-derive.**

It starts from a small frozen interpreter that knows nothing: no types, no
modules, no languages, no editors. Everything else - the languages, the change
calculus, the version control, the publication ledger, the governance, the
editors - is built as data that the interpreter runs, and each layer is derived
from the one beneath it by a single recorded change.

By the top of the staircase that is three things, not one: a **workbench**
where a language arrives with its own tooling and its own editor; a
**repository** whose history is a causal graph of semantic patches rather than
text diffs; and a **publication chain** where a change is accepted under a
constitution and settled between federated peers, so that a fresh node can
reconstruct the whole state from the chain and nothing else. The
[publication workflow](docs/publication-workflow.md) is the cycle that joins
them.

The point is that nothing above the frozen host boundary is accepted when it
can instead be re-derived:

- every layer is **rebuilt from source** on every run, and its identity is the
  SHA-256 of what it actually is, so drift is impossible to hide;
- a layer is **constructed** from its predecessor plus one change, rather than
  merely checked against it, so succession is a derivation and not an
  assertion;
- **two independent implementations** - one in Scala, one in Rust with no
  dependencies at all - agree on every verdict, byte for byte;
- the whole thing **reconstructs in a clean room** from an executable, a digest
  and a closure, with no source tree present.

What remains trusted is worth naming, because a claim that hides its own root
is not much of a claim: the executable you start from, the compiler and runtime
that produced and run it, SHA-256 behaving as assumed, and the committed frozen
core. Clean-room reconstruction shrinks that root to three things; it does not
abolish it.

```text
F0  ->  F1  ->  ...  ->  F12
  each arrow is one canonical change, and the predecessor validates it
```

### Try it

```bash
./tools/staircase.sh    # rebuild all twelve layers and check every digest
sbt test                # replay every transcript
./tools/parity.sh       # make the two hosts agree
./tools/cleanroom.sh    # rebuild from executable, digest and closure alone
```

Start reading at [docs/staircase.md](docs/staircase.md) for how the layers
stack up, or [docs/invariants.md](docs/invariants.md) for what is actually
guaranteed. The original specification is [PROMPT.md](PROMPT.md).

## The shape of the system

```text
StratumHost0 (frozen native bootstrap)
  Canon, artifacts, SHA-256 identity, local CAS, closure traversal,
  MetaMachine0, GrammarMachine0, generic capability dispatch, budgets, evidence

Feature artifacts (everything else)
  languages, change calculi, repositories, toolchains, governance,
  foundations, publication, synchronization, agreement, retention, studios
```

The host never learns what a feature means. It accepts a program, a goal, a
closure, a constitution, a budget and capabilities, and returns a canonical
verdict with evidence.

## Layout

| Path | Contents |
| --- | --- |
| [host-scala](host-scala) | the frozen bootstrap host |
| [host-rust](host-rust) | an independent bootstrap host, no dependencies |
| [verifier-lean](verifier-lean) | the derivation model and its determinism proof |
| [languages](languages) | Meta, Grammar and object language artifacts |
| [features](features) | canonical feature artifacts added by each step |
| [profiles](profiles) | studio profiles |
| [foundations](foundations) | one directory per foundation, with its closure |
| [changes](changes) | the canonical change between consecutive foundations |
| [fixtures](fixtures) | transcripts, the functional acceptance mechanism |
| [test-scala](test-scala) | transcript replay, canon properties, boundary gate, host parity |
| [docs](docs) | invariants, staircase, native boundary, per-foundation notes |

## The staircase

| Step | Adds | Checks | Notes |
| --- | --- | --- | --- |
| F0 | canonical local lambda world | 10 | [F0](docs/foundations/F0.md) |
| F1 | self-described Meta and Grammar | 17 | [F1](docs/foundations/F1.md) |
| F2 | generic semantic change calculus | 20 | [F2](docs/foundations/F2.md) |
| F3 | local causal semantic repository | 17 | [F3](docs/foundations/F3.md) |
| F4 | schema-derived language toolchains | 17 | [F4](docs/foundations/F4.md) |
| F5 | constitution-relative governance | 23 | [F5](docs/foundations/F5.md) |
| F6 | self-hosted application and foundation protocol | 11 | [F6](docs/foundations/F6.md) |
| F7 | signed publication ledger | 12 | [F7](docs/foundations/F7.md) |
| F8 | distributed closure and branch synchronization | 12 | [F8](docs/foundations/F8.md) |
| F9 | constituted agreement and settlement | 12 | [F9](docs/foundations/F9.md) |
| F10 | constituted semantic retention and archives | 14 | [F10](docs/foundations/F10.md) |
| F11 | profile-guided studios and the publication workflow | 27 | [F11](docs/foundations/F11.md) |
| F12 | semantic filesystem projections and materialization-profile law | 0 | [F12](docs/foundations/F12.md) |

## Quick start

```bash
cargo build --release --manifest-path host-rust/Cargo.toml
sbt test                      # transcripts, canon properties, boundary and freeze gates, host parity
./tools/staircase.sh          # rebuild every foundation, and derive each one from its predecessor
./tools/parity.sh             # two host agreement on encodings, verdicts and attestations
./tools/cleanroom.sh          # reconstruct from executable + digest + closure only
cd verifier-lean && lake build
```

## Distribution

Build a portable command-line archive (Java 17 or newer):

```bash
sbt distribution
```

The archive is written to `target/stratum-<version>.zip` with Unix and Windows
launchers. Version tags matching `v*` publish the archive and its SHA-256
checksum as a GitHub release. See [RELEASING.md](RELEASING.md) for the release
checklist and the licensing decision required before the first public release.

After extracting it, run `sh bin/stratum` on Unix or `bin\stratum.bat` on
Windows.

## The bootstrap closure

| Claim | Gate |
| --- | --- |
| $F_n \vdash F_{n+1}$ constructively | `foundation derive-successor`, run for every step by [tools/staircase.sh](tools/staircase.sh) |
| $\mathsf{ScalaDerive} = \mathsf{RustDerive}$ | [tools/parity.sh](tools/parity.sh) compares every verdict digest, including evidence and resource accounting |
| a runbook still works | [RunbookSuite](test-scala/RunbookSuite.scala) rehearses every [runbook](runbooks), performing it in full and keeping nothing |
| canonical bytes mean one thing | [fixtures/canon/adversarial](fixtures/canon/adversarial), judged identically by both hosts |
| the host core is frozen | [host/core.canon](host/core.canon), referenced by F1..F11 and checked by [test-scala/HostCoreSuite.scala](test-scala/HostCoreSuite.scala) |
| reconstruction needs nothing else | [tools/cleanroom.sh](tools/cleanroom.sh) |
| the calculus is deterministic | [verifier-lean/Stratum/Meta0.lean](verifier-lean/Stratum/Meta0.lean) |

Rebuild a foundation from its declarative spec:

```bash
sbt "runMain stratum.cli.Stratum foundation build --spec foundations/F0/build.canon --out foundations/F0"
```

Regenerate everything elaborated from a surface source:

```bash
./tools/regenerate.sh
```

Check the two hosts against each other:

```bash
cargo build --release --manifest-path host-rust/Cargo.toml
./tools/parity.sh
```

Regenerate the frozen host core identity after a deliberate change to the fixed
calculus:

```bash
sbt "runMain stratum.cli.Stratum host manifest --out host/core.canon"
```

## A concrete Stratum repository

The semantic repository tool is a separate program above the frozen host
boundary. It records file content as immutable blobs, a canonical file tree as
a semantic add/remove/replace patch, and anchors that patch in an append-only
chain:

```bash
sbt "repoTool/runMain stratum.repo.StratumRepo init --dir ../stratum-repo"
sbt "repoTool/runMain stratum.repo.StratumRepo record --dir ../stratum-repo --source . --message stratum-genesis"
sbt "repoTool/runMain stratum.repo.StratumRepo verify --dir ../stratum-repo"
sbt "repoTool/runMain stratum.repo.StratumRepo status --dir ../stratum-repo --source ."
sbt "repoTool/runMain stratum.repo.StratumRepo log --dir ../stratum-repo"
sbt "repoTool/runMain stratum.repo.StratumRepo branch --dir ../stratum-repo --name featured/example --from main"
sbt "repoTool/runMain stratum.repo.StratumRepo checkout --dir ../stratum-repo --branch featured/example --out ../example"
```

The mutable `refs/main` file names only the current block. Blobs, trees,
patches, and blocks live in the canonical content-addressed object store, and
verification traverses the complete closure and predecessor chain.

## Transcripts

Functional behaviour is proven by transcripts, not by inspecting host internals.
A transcript records commands and their exact canonical output:

```text
$ derive --foundation foundations/F0 --goal '(call AlphaEquivalent (grammar lambda) (q "\\x. x") (q "\\y. y"))'
> #t
```

Regenerate expectations after an intentional change:

```bash
sbt "runMain stratum.cli.Stratum transcript run fixtures --update"
```

## Branches

The staircase is the platform. What stands on it is separable, so it lives
apart:

| Branch | Carries |
| --- | --- |
| `main` | the platform: the twelve foundations, both hosts, the Lean model, the shared languages, and the generic language service |
| `featured/*` | one branch per application, and one per editor client |

Everything shareable stays here, including
[languages/pdf](languages/pdf), the projection that turns a document into an
actual PDF, and [languages/service](languages/service), the generic language
service every world can bind to. What a branch adds is a world of its own: a
language, its documents and its profiles, built on the finished platform.

## Editing

[host-scala/lsp](host-scala/lsp) is a language server for **every language a
world publishes**, and it knows no language. The host core is frozen, so the
server may not learn what a diagnostic means: it converts JSON, framing and
offsets, and everything displayed is derived by a judgment of the world.

| A language provides | It gets |
| --- | --- |
| a grammar, and nothing else | syntax errors, highlighting, completion, formatting |
| `ServiceSymbols` | outline, hover, breadcrumbs |
| a Studio profile | views and commands |

See [docs/studio.md](docs/studio.md).

## Documentation

Rendered at **<https://eurisko-info-lab.github.io/stratum/>**, and readable in
the repository as the same files:

- [docs/staircase.md](docs/staircase.md) — how one foundation constructs the next
- [docs/invariants.md](docs/invariants.md) — the properties every layer must hold
- [docs/native-boundary.md](docs/native-boundary.md) — what the host is allowed to know
- [docs/studio.md](docs/studio.md) — how an editor is derived from a profile
- [docs/publication-workflow.md](docs/publication-workflow.md) — how a release is produced
- [docs/foundations](docs/foundations) — one page per layer

## How this is written

Every line of code here is written by an AI agent. Humans set the direction,
review what comes back and decide what ships; the code itself is
machine-written. The gates exist so that this is a testable claim rather than a
boast — see [docs/sponsoring.md](docs/sponsoring.md).

## Licence

[Apache-2.0](LICENSE). The `NOTICE` of authorship is in the licence appendix.
