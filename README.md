# Stratum

Stratum is a twelve-step semantic staircase. Each foundation is produced from the
one beneath it by a single canonical change, and the predecessor validates the
successor.

$$
F_0 \prec F_1 \prec \cdots \prec F_{11}
$$

Each commit advances the exact materialization image of its parent. F0 is the
only root image; later commits apply and verify only their own transition. See
[materialization images](docs/images.md) for the storage and trust model.

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
| [languages](languages) | Meta, Grammar and object language artifacts |
| [features](features) | canonical feature artifacts added by each step |
| [foundations](foundations) | one directory per foundation, with its closure |
| [changes](changes) | the canonical change between consecutive foundations |
| [fixtures](fixtures) | transcripts, the functional acceptance mechanism |
| [test-scala](test-scala) | transcript replay, canon properties, boundary gate |
| [docs](docs) | invariants, staircase, native boundary, per-foundation notes |

## Quick start

```bash
sbt test                                                     # replay everything
sbt "runMain stratum.cli.Stratum host info"
sbt "runMain stratum.cli.Stratum foundation verify --dir foundations/F0"
sbt "runMain stratum.cli.Stratum transcript run fixtures"
./tools/image.sh restore IMAGE COMMIT
```

Rebuild a foundation from its declarative spec:

```bash
sbt "runMain stratum.cli.Stratum foundation build --spec foundations/F0/build.canon --out foundations/F0"
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

## Documentation

- [docs/invariants.md](docs/invariants.md)
- [docs/staircase.md](docs/staircase.md)
- [docs/native-boundary.md](docs/native-boundary.md)
- [docs/foundations](docs/foundations)
