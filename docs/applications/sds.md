# The SDS deployment

```text
applications/sds
```

An application branched from F11. Not a step of the bootstrap staircase: the
platform is finished, and this is what it is for.

## What it reuses

Meta and Grammar elaboration, the free-change calculus, the causal repository,
schema-derived tooling, constitution-relative governance, the publication
ledger, closure synchronization, constituted agreement, retention and the
generic Studio runtime. The deployment adds only its own language, profiles and
documents.

`foundation derive-successor` shows that F11 constructs it:

```text
F11 |- sds
```

## The object language

[applications/sds/sds.grammar](../../applications/sds/sds.grammar) declares a
real document syntax:

```text
document "Acetone technical data"
section 1 "Identification"
  sentence product "Acetone"
  field boiling-point 65 celsius
section 3 "Composition"
  component cas-67-64-1 99
```

[applications/sds/sds.meta](../../applications/sds/sds.meta) gives it meaning:
parsing, canonical printing, a symbol index over stable keys, and a validation
pass that reports `unknown-unit`, `fraction-out-of-range` and
`severity-out-of-range` findings.

## One coherent evolution

### The language changes

[applications/sds/sds-v2.grammar](../../applications/sds/sds-v2.grammar) adds a
regulated hazard statement:

```text
hazard h225 "Highly flammable liquid and vapour" 2
```

Version 1 rejects that document; version 2 accepts it and still accepts every
version 1 document. Both grammars are elaborated by F11's Grammar elaborator, so
the evolution is a language change, not a parser edit.

### The surface changes

`DeployedProfileV1` has panels `sections entries findings preview`.
`DeployedProfileV2` adds `hazards` and the `classify-hazard` command. The
language digest is unchanged: a surface change is not a semantic change.

### The data changes

The boiling point is corrected from 65 to 56 and the hazard statement is added.

## The loop

| Stage | Judgment |
| --- | --- |
| record three patches on one branch | `EvolutionRepository` |
| language and surface patches commute | `LanguageAndSurfaceCommute` |
| replay the branch | `EvolvedWorkspace` |
| accept under a constitution | `AcceptedRelease` |
| block on missing evidence | `RejectedRelease` |
| publish to the chain | `ReleaseChain` |
| settle the transition | `ReleaseSettled` |
| reconstruct from chain plus closure | `ReconstructedMatchesPublished` |
| branch from the finalized publication | `BranchFromRelease` |
| reopen the studio | `ReopenedPanels`, `ReopenedHazards` |

## Acceptance

Thirty checks in
[applications/sds/checks.canon](../../applications/sds/checks.canon) and the
transcript [fixtures/sds/sds.transcript](../../fixtures/sds/sds.transcript).

The deployment is covered by the same gates as the foundations: the independent
Rust host reproduces all thirty verdicts byte for byte, the clean room
reconstructs it from executable plus digest plus closure, and F11 derives its
digest from the canonical change alone.
