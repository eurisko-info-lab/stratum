# Stratum

**Stratum builds a programming system out of itself, one provable step at a
time, and never trusts a step it cannot re-derive.**

It starts from a small frozen interpreter that knows nothing: no types, no
modules, no languages, no editors. Everything else — the languages, the change
calculus, the version control, the governance, the editors — is built as data
that the interpreter runs, and each layer is derived from the one beneath it by
a single recorded change.

The point is that nothing above the frozen host boundary is accepted when it
can instead be re-derived:

- every layer is **rebuilt from source** on every run, and its identity is the
  SHA-256 of what it actually is, so drift is impossible to hide;
- a layer is **constructed** from its predecessor plus one change, rather than
  merely checked against it, so succession is a derivation and not an
  assertion;
- **two independent hosts** — one in Scala, one in Rust with no dependencies —
  must agree on every digest, so a result cannot be an artefact of one
  implementation;
- any layer can be **rebuilt clean-room** from its executable, its digest and
  its closure alone.

## Try it

```bash
git clone https://github.com/eurisko-info-lab/stratum
cd stratum
./tools/image.sh restore IMAGE COMMIT
sbt test                # replay every transcript
./tools/parity.sh       # make the two hosts agree
./tools/cleanroom.sh    # rebuild from executable, digest and closure alone
```

Nothing is downloaded that the build cannot name, and nothing passes that the
build cannot re-derive.

## What is on the staircase

A language workbench is only one of the three things the layers build, and the
other two are the reason the first one is interesting.

**A workbench.** Languages describe themselves, grammars and toolchains are
derived from schemas rather than written, and an editor is derived from a
profile — so a new language arrives with its own tooling instead of needing it
built afterwards.

**A repository.** History here is a causal graph of semantic patches, not a
sequence of text diffs. Independent patches commute, conflicts are explicit
rather than textual, branches are taken from checkpoints, and a peer
synchronises by fetching a closure addressed by digest. Archives keep what is
reachable and collect the rest reconstructibly.

**A publication chain.** A change is accepted under a constitution, packaged as
a proposal, and settled onto a signed ledger between federated peers with BFT
finality and evidence for equivocation. A fresh node reconstructs the whole
state from the chain and nothing else, and the editors reopen on it.

Those are not three systems that were integrated. Each is a layer derived from
the one beneath it, built as data the frozen interpreter runs, and the
[publication workflow](publication-workflow.md) is the cycle that joins them:
from a settled publication, through a local branch and a recorded change, back
to a settled publication.

## Where to start reading

| | |
| --- | --- |
| [The staircase](staircase.md) | how one foundation constructs the next, and what a change is |
| [Strata second floor](strata.md) | language and bootstrap architecture for the post-F11 implementation staircase |
| [The publication workflow](publication-workflow.md) | the whole cycle: branch, patch, accept, publish, settle, reconstruct |
| [Invariants](invariants.md) | the properties every layer must hold, and where each is enforced |
| [The native boundary](native-boundary.md) | what the host is allowed to know, and why it is so little |
| [Studio](studio.md) | how an editor is derived from a profile rather than written |
| [Foundations F0–F14](foundations/F0.md) | one page per layer: what it adds and what it proves |

## The staircase layers

Each foundation is a complete system that can run the one above it. The
staircase is not a build order — it is a sequence of derivations, each recorded
as a change that the predecessor validates.

| | |
| --- | --- |
| [F0](foundations/F0.md) | the seed: a frozen interpreter and a lambda language |
| [F1](foundations/F1.md) | Meta and Grammar describe themselves |
| [F2](foundations/F2.md) | changes become a language with its own calculus |
| [F3](foundations/F3.md) | causal patch graphs and branches |
| [F4](foundations/F4.md) | toolchains derived from language schemas |
| [F5](foundations/F5.md) | claims, proofs and acceptance |
| [F6](foundations/F6.md) | self-hosted applications, packaged |
| [F7](foundations/F7.md) | a signed publication ledger |
| [F8](foundations/F8.md) | closure and branch synchronisation |
| [F9](foundations/F9.md) | federation with BFT finality |
| [F10](foundations/F10.md) | semantic archives and reconstruction |
| [F11](foundations/F11.md) | profile-guided editors, knowing no domain |
| [F12](foundations/F12.md) | semantic filesystem authority and governed materialization |
| [F13](foundations/F13.md) | canonical schema reflection, identities, and structural change law |
| [F14](foundations/F14.md) | investigation-state agents with compiled context and evidence loops |

Each layer is tagged, and each tag verifies on its own terms. The digest a tag
records is the digest that layer had when it was built; later layers re-derive
the whole staircase, so those digests move, and that movement is the mechanism
working rather than a fault in it.

## Branches

The staircase is the platform. What stands on it is separable, so it lives
apart:

| Branch | Carries |
| --- | --- |
| `main` | the platform: F0..F11 plus the revised F12/F13/F14 candidate sequence, both hosts, the Lean model, the shared languages, and the generic language service |
| `featured/*` | one branch per application, and one per editor client |

The platform names no application and no editor. A branch adds a world of its
own — a language, its documents, its profiles — built on the finished platform
and constructed from it by a single recorded change, exactly as one foundation
constructs the next.

## How this is written

Every line of code here is written by an AI agent. That is a deliberate
constraint rather than an accident of tooling, and it is the reason the project
is unusually strict about what counts as evidence: a claim that cannot be
re-derived by a machine is not worth making.

The most useful thing anyone can do to a system that claims to be re-derivable
is to check, and say so loudly when it is not — see
[Contributing](https://github.com/eurisko-info-lab/stratum/blob/main/CONTRIBUTING.md).

[Read more, including how to support it](sponsoring.md).
