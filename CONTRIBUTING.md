# Contributing

Stratum is written by AI agents. Humans set the direction, review what comes
back and decide what ships; the code itself is machine-written. That makes
contributing here different from most projects, so this page says plainly what
is useful and what is not.

## The most useful thing you can do

**Try to break a claim.**

The project asserts that every layer is rebuilt from source, agreed on by two
independent hosts, and reconstructible from its digest and closure alone. Those
are testable claims, and the whole point of making them mechanically is that
anyone can check:

```bash
git clone https://github.com/eurisko-info-lab/stratum
cd stratum
./tools/staircase.sh    # rebuild all twelve layers and check every digest
sbt test                # replay every transcript
./tools/parity.sh       # make the two hosts agree
./tools/cleanroom.sh    # rebuild from executable, digest and closure alone
```

If any of that fails on your machine, or succeeds while it should not, that is
the most valuable issue this repository can receive. Please include your OS,
JDK, Scala and Rust versions, and the full output.

A close second: a case where a gate *passes* something it ought to catch. A
shape nothing reads is a shape nothing can disagree with, and that class of bug
has already shipped here more than once.

## Issues and questions

Both are welcome, and are read by a human. Arguing with a design decision is
fair game and costs you nothing; if you think a layer is in the wrong place or
an invariant is wrong, say so.

## Code changes

Pull requests sit oddly against the constraint above, and we would rather be
honest about that up front than leave you to find out after the work is done.

If you have a change you want made, the most direct route is an issue that
describes it precisely enough for an agent to implement and for the gates to
judge — which, if the change is a good one, is a description worth having
anyway. State what should become true, and how anyone would know it had. That
is the same standard the code here is held to.

We may still accept a patch. Ask in an issue first.

## What any change has to survive

Whether written by a person or an agent, nothing lands until all of this is
green:

| | |
| --- | --- |
| `./tools/regenerate.sh` | every `.meta` and `.grammar` source re-elaborated |
| `./tools/staircase.sh` | all twelve layers rebuilt, every digest checked for drift |
| `./tools/deployment.sh` | every application rebuilt and constructed from the platform |
| `sbt test` | canon properties, the native boundary gate, every transcript |
| `./tools/parity.sh` | the Scala and Rust hosts agree digest for digest |
| `./tools/cleanroom.sh` | every world rebuilt from executable, digest and closure alone |

A change to a world also has to re-derive the canonical change by which its
predecessor constructs it. Building without re-deriving leaves a world that
verifies and cannot be reconstructed, which the deployment gate will catch.

## Boundaries that are not negotiable

These are the constraints the design rests on. A change that needs one of them
relaxed is a change that needs discussing first.

- **The host core is frozen.** `host-scala/cli/HostCore.scala` fixes the canon
  tags, the Meta0 forms, the primitives and the verdict shapes. New adapters,
  additional host implementations and diagnostics that cannot affect a verdict
  are outside the freeze; new primitives, forms and tags are not.
- **The host knows no vocabulary of the system above it.** `NativeBoundarySuite`
  forbids feature words in `host-scala/**` and forbids dispatching on tags the
  system defines. Data crossing that boundary travels in maps read by name, so
  the host dispatches on nothing.
- **The platform names no application and no editor.** `main` carries the
  staircase and what is shareable; a world lives on its own `featured/*` branch
  and is constructed from the finished platform by one recorded change.
- **Generated artefacts are committed.** A closure has to be complete without
  running the generator, and the drift gate has to be able to prove nothing has
  gone stale.

## Two things that will bite you

Both have cost real time here, and neither is obvious:

- **Judgment names are global** across every program merged into a world, and a
  collision silently overrides. Before adding one:
  `grep -ho '^judgment [A-Za-z0-9_-]*' -r --include=*.meta . | awk '{print $2}' | sort | uniq -d`
- **Meta booleans are `true` and `false`.** `#t` and `#f` are *symbols* in
  Meta source; they mean booleans only in canon text. The mistake type-checks
  and gives wrong answers.

## Commit messages

They explain why the change was necessary and what it cost — including the
approach that did not work, when that is the useful part. A message that only
restates the diff is not worth the line it takes up.

## Licence

By contributing you agree that your contribution is licensed under
[Apache-2.0](LICENSE), the same terms as the rest of the project.
