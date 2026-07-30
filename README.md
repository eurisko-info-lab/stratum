# Stratum

[![staircase](https://github.com/eurisko-info-lab/stratum/actions/workflows/staircase.yml/badge.svg)](https://github.com/eurisko-info-lab/stratum/actions/workflows/staircase.yml)

Stratum is a twelve-step semantic staircase. Each foundation is produced from the
one beneath it by a single canonical change, and the predecessor validates the
successor.

$$
F_0 \prec F_1 \prec \cdots \prec F_{11}
$$

The complete specification is [PROMPT.md](PROMPT.md).

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
| F5 | constitution-relative governance | 20 | [F5](docs/foundations/F5.md) |
| F6 | self-hosted application and foundation protocol | 11 | [F6](docs/foundations/F6.md) |
| F7 | signed publication ledger | 12 | [F7](docs/foundations/F7.md) |
| F8 | distributed closure and branch synchronization | 12 | [F8](docs/foundations/F8.md) |
| F9 | constituted agreement and settlement | 12 | [F9](docs/foundations/F9.md) |
| F10 | constituted semantic retention and archives | 14 | [F10](docs/foundations/F10.md) |
| F11 | profile-guided studios and the publication workflow | 27 | [F11](docs/foundations/F11.md) |

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

- [docs/invariants.md](docs/invariants.md)
- [docs/staircase.md](docs/staircase.md)
- [docs/native-boundary.md](docs/native-boundary.md)
- [docs/publication-workflow.md](docs/publication-workflow.md)
- [docs/studio.md](docs/studio.md)
- [docs/foundations](docs/foundations)

## Licence

[Apache-2.0](LICENSE). The `NOTICE` of authorship is in the licence appendix.
