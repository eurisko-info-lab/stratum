# PROMPT: Build Stratum as a Twelve-Step Semantic Staircase

Build **Stratum**, a sequence of self-preserving semantic foundations:

$$
F_0 \prec F_1 \prec \cdots \prec F_{11}
$$

Each foundation is constructed by branching the preceding foundation, changing its Meta and Grammar languages, adding exactly one coherent semantic feature, reconstructing the successor, and promoting it as the basis for the next step.

The central relation is:

$$
F_n
\xrightarrow{\delta_n : \Delta F_n}
F_{n+1}
$$

with:

$$
\delta_n =
(
\delta_n^{Meta},
\delta_n^{Grammar},
\delta_n^{Feature},
\delta_n^{Foundation}
)
$$

The predecessor must validate the successor:

$$
F_n
\vdash
\mathsf{Build}(F_n,\delta_n)
\Downarrow
F_{n+1}
$$

The project must not begin by implementing the complete present-day Cairn architecture in Scala.

Begin with the smallest useful foundation:

$$
F_0 =
\text{local untyped lambda calculus over canonical artifacts}
$$

Then add one feature per commit until the final foundation provides:

* self-described Meta and Grammar languages;
* free change languages;
* a Pijul-like semantic repository;
* generated parsers, printers, interpreters, compilers and editors;
* claims, proofs and acceptance constitutions;
* self-hosted application and foundation manifests;
* a signed publication blockchain;
* synchronization and closure exchange;
* Byzantine federation and finality;
* semantic retention and archives;
* local repositories branched from the chain;
* publication of language, code and data changes back to the chain;
* schema-guided Smalltalk and SDS development environments with distinct interfaces.

The complexity added at each step must live primarily in canonical feature artifacts, Meta rules, Grammar declarations and schemas.

It must not accumulate as new native Scala subsystems.

---

# 1. The immutable bootstrap boundary

Implement one small native bootstrap host in Scala 3.

Call it:

```text
StratumHost0
```

Its permanent responsibilities are:

```text
Canon
Artifact envelopes
SHA-256 identity
Local CAS
Closure traversal
MetaMachine0
GrammarMachine0
Generic capability dispatch
Explicit resource budgets
Canonical evidence encoding
```

The host must not know about:

* lambda calculus;
* types;
* repositories;
* patches;
* compilers;
* acceptance policies;
* blockchains;
* synchronization;
* BFT;
* SDS;
* Smalltalk;
* editor panels.

Those concepts must be feature artifacts interpreted by the bootstrap host.

## 1.1 MetaMachine0

MetaMachine0 is a tiny fixed calculus supporting:

* algebraic data;
* records;
* sums and products;
* lists and maps;
* indexed data families;
* typed judgments;
* explicit input/output modes;
* deterministic rules;
* pattern matching;
* structural recursion;
* explicit decreasing measures;
* bounded folds;
* canonical evidence;
* explicit capability requests.

It excludes:

* unrestricted recursion;
* mutation;
* exceptions as semantic control flow;
* hidden I/O;
* host reflection;
* native feature plugins;
* arbitrary foreign calls;
* higher-order unification;
* nondeterministic search;
* unspecified rule priority.

Its execution relation is:

$$
\mathsf{derive}
(
P,\Sigma,K,B,G
)
=

V
$$

where:

* (P) is a canonical Meta program;
* (\Sigma) is a resolved immutable closure;
* (K) is a Kernel constitution;
* (B) is an explicit budget;
* (G) is a canonical goal;
* (V) is a canonical verdict with evidence.

## 1.2 GrammarMachine0

GrammarMachine0 interprets canonical grammar artifacts supporting:

* token declarations;
* lexical classes;
* recursive categories;
* constructors;
* field labels;
* precedence;
* associativity;
* parsing;
* canonical printing;
* source spans.

It does not contain syntax for any particular language.

## 1.3 Generic capability dispatch

The native host may expose operational primitives through a generic request/response interface.

Initial and later capability handlers may include:

* filesystem access;
* local CAS access;
* hashing;
* signature creation and verification;
* clock observations;
* random-byte generation;
* network send and receive;
* external process invocation.

The capability handler performs operations.

Meta programs decide what those operations mean.

For example, native code may answer:

```text
VerifySignature(key, message, signature)
```

but native code must not decide:

* whether the signer has authority;
* whether the message is an accepted change;
* whether a quorum is sufficient;
* whether a transition is finalized.

---

# 2. Native-code freeze

After foundation (F_1), freeze the semantic native host.

From (F_2) onward, a feature commit must not add:

* a Scala class representing the new feature’s semantics;
* a new host-language validator;
* a feature-specific interpreter;
* a feature-specific replay loop;
* a feature-specific policy branch;
* a feature-specific editor implementation;
* a new `match` over domain feature tags.

Permitted native changes after (F_1) are limited to:

* correctness fixes in the fixed bootstrap implementation;
* semantics-neutral performance improvements;
* new generic capability adapters;
* additional independent implementations of the same fixed bootstrap interface;
* improved diagnostics that do not affect canonical verdicts.

Add an architectural test that rejects feature-specific imports or semantic dispatch inside the bootstrap modules.

The desired asymmetry is:

```text
Native host:
  program
  goal
  closure
  constitution
  budget
  capabilities

Feature artifacts:
  everything else
```

---

# 3. Foundation invariants

Every (F_n) must satisfy the following invariants.

## 3.1 Canonical identity

Every authoritative value has one canonical representation:

$$
\mathsf{decode}(\mathsf{encode}(x)) = x
$$

Its identity is:

$$
\mathsf{id}(x) =
H(\mathsf{encode}(x))
$$

Non-canonical encodings are rejected.

## 3.2 Closure completeness

A foundation is reconstructible from:

```text
foundation digest
+
complete immutable artifact closure
```

No semantic result may depend on:

* classpath discovery;
* mutable global registries;
* environment defaults;
* source-tree paths;
* process-local object identity;
* network resources not represented in the input closure.

## 3.3 Deterministic interpretation

For fixed semantic inputs:

$$
\mathsf{derive}(P,\Sigma,K,B,G)=V_1
$$

and:

$$
\mathsf{derive}(P,\Sigma,K,B,G)=V_2
$$

imply:

$$
V_1=V_2
$$

including evidence and deterministic resource accounting.

## 3.4 Parse and print

For every canonical language artifact:

$$
\mathsf{parse}(\mathsf{print}(a))=a
$$

Concrete source preservation may use source maps, but canonical AST identity must round-trip.

## 3.5 Every language has a change language

For every language (L), provide:

$$
\Delta L
$$

and:

$$
\mathsf{Apply}_L:
L\times\Delta L\to L
$$

This includes:

* Meta;
* Grammar;
* object languages;
* application manifests;
* foundations;
* constitutions;
* repository formats;
* chain formats;
* federation formats;
* Studio profiles.

## 3.6 Explicit authority

Every accepted transition identifies:

* the constitution;
* the proposed change;
* the authority;
* the evidence;
* the decision;
* the predecessor;
* the successor.

Even the earliest single-user acceptance rule must be represented explicitly.

## 3.7 Predecessor-verified succession

For every step:

$$
F_n
\vdash
F_{n+1}\ \mathsf{valid}
$$

The successor must then reconstruct itself from its own closure.

## 3.8 Semantic and surface separation

A language’s meaning and its editor presentation are separate artifacts.

Changing a panel layout must not change the language’s semantic digest.

The same language may have multiple Studio profiles.

The same Studio machinery may render radically different profiles.

---

# 4. Commit and promotion protocol

Produce exactly one coherent semantic feature per commit.

Each commit constructs one new foundation.

For commit (n):

1. branch from the exact promoted (F_{n-1});
2. define `delta-meta`;
3. define `delta-grammar`;
4. define the new feature artifacts;
5. define or derive their change languages;
6. apply the changes using (F_{n-1});
7. construct (F_n);
8. verify (F_n) using (F_{n-1});
9. start a fresh process;
10. reconstruct (F_n) from its digest and closure;
11. run all earlier foundation tests;
12. commit the resulting canonical artifacts;
13. tag the commit `foundation/Fn`.

Every commit must contain:

```text
foundations/Fn/foundation.canon
foundations/Fn/application.canon
foundations/Fn/closure/
foundations/Fn/verdict.canon
foundations/Fn/evidence.canon

changes/F(n-1)-Fn/change.canon
changes/F(n-1)-Fn/delta-meta.canon
changes/F(n-1)-Fn/delta-grammar.canon

docs/foundations/Fn.md
```

The Git commit records the implementation event.

The canonical foundation change is the authoritative semantic event.

Do not squash the foundation commits.

---

# 5. Foundation sequence

Use the following twelve commits.

---

# Commit 0: `seed: create F0 Scala lambda foundation`

## Feature

Create the minimal Scala bootstrap and one local untyped lambda-calculus world.

This is the only foundation initially authored directly in Scala.

## Native implementation

Implement:

```text
Canon
Digest
Artifact
ArtifactKind
LocalCas
Closure
MetaMachine0
GrammarMachine0
Budget
Evidence
CapabilityRequest
CapabilityResponse
BootstrapFoundationManifest
```

Provide only the capability handlers needed for:

* local filesystem access;
* CAS access;
* hashing.

Define a generic capability ABI that later handlers can implement without changing MetaMachine0.

## Canonical language artifacts

Define the untyped lambda language:

```text
Term =
    Var(Name)
  | Lam(Name, Term)
  | App(Term, Term)
```

Define canonical syntax such as:

```text
x
\x. body
function argument
(function) argument
```

Define judgments:

```text
ParseLambda
PrintLambda
Substitute
StepNormalOrder
Normalize
AlphaEquivalent
```

Use de Bruijn indices or a locally nameless representation internally so substitution and alpha-equivalence remain deterministic.

## Bootstrap changes

Before generic FreeChange exists, every language has a minimal replacement change:

```text
ReplaceChange {
  targetKey
  expectedBefore
  replacement
}
```

The replacement is predecessor-bound and cannot silently overwrite a different value.

Provide replacement change languages for:

* Meta0 programs;
* Grammar0 grammars;
* lambda programs;
* bootstrap foundations.

## Local history

Provide only a predecessor-linked sequence of foundation roots:

```text
FoundationRoot {
  predecessor
  application
  meta
  grammar
  closure
}
```

Do not implement:

* named branches;
* patch DAGs;
* networking;
* synchronization;
* blockchain blocks;
* signatures;
* federation;
* BFT.

## Acceptance tests

Prove by executable tests:

* lambda parse/print round-trip;
* deterministic beta reduction;
* alpha-equivalence stability;
* closure reconstruction in a fresh process;
* stable (F_0) digest;
* predecessor-bound replacement rejection;
* resource exhaustion returns a canonical verdict;
* no networking or blockchain classes exist.

## Result

$$
F_0 =
\text{canonical local lambda world}
$$

---

# Commit 1: `meta: self-describe Meta and Grammar in F1`

## Feature

Make Meta and Grammar ordinary languages interpreted by the bootstrap machines.

After this commit, the Scala bootstrap must no longer contain the evolving Meta or Grammar definitions.

## Meta change

Define `Meta1` using Meta0.

Meta1 provides surface declarations for:

```text
data
record
constructor
judgment
rule
input
output
measure
capability
evidence
module
import
```

Meta1 elaborates to canonical Meta0.

## Grammar change

Define `Grammar1` using Meta1 and Grammar0.

Grammar1 provides declarations for:

```text
token
keyword
category
constructor syntax
precedence
associativity
field label
source span
canonical print
```

Grammar1 elaborates to canonical Grammar0.

## Self-description

The foundation must:

1. load Meta1 from artifacts;
2. parse Meta1’s own source;
3. reconstruct the canonical Meta1 AST;
4. load Grammar1;
5. parse Grammar1’s own grammar;
6. reconstruct its canonical grammar AST;
7. reconstruct the lambda language;
8. derive the same lambda behavior as (F_0).

Require:

$$
\mathsf{Meta0}(\mathsf{Meta1})=\mathsf{Meta1}
$$

at the canonical artifact level.

## Native freeze gate

After this commit:

* Meta0 is immutable;
* Grammar0 is immutable;
* Meta1 and Grammar1 evolve through their own change languages;
* new features must not introduce host semantic logic.

## Acceptance tests

* Meta1 parse/print round-trip;
* Grammar1 parse/print round-trip;
* self-description fixpoint;
* lambda behavior identical under (F_0) and (F_1);
* no host-built lambda AST or evaluator is used;
* fresh-process reconstruction.

## Result

$$
F_1 =
F_0
+
\text{self-described Meta and Grammar}
$$

---

# Commit 2: `changes: derive free change languages in F2`

## Feature

Replace bootstrap-level whole-object replacement with generic structural free changes.

## Meta change

Add a universe of structural descriptions:

```text
Desc =
    Unit
  | Bool
  | Nat
  | Integer
  | Bytes
  | String
  | Digest
  | Sum
  | Product
  | Record
  | List
  | Map
  | Option
  | Reference
  | Recursive
  | Binder
  | Indexed
```

Define:

$$
\mathsf{El}:\mathsf{Desc}\to\mathsf{Type}
$$

Define:

$$
\mathsf{FreeChange}:\mathsf{Desc}\to\mathsf{Desc}
$$

and:

$$
\mathsf{Apply}:
\mathsf{El}(D)\times
\mathsf{El}(\mathsf{FreeChange}(D))
\to
\mathsf{El}(D)
$$

## Grammar change

Add syntax for:

```text
fieldId
constructorId
key
path
change
insert
delete
replace
move
rename
edit
```

Stable semantic paths must use:

* field identities;
* constructor identities;
* keyed collection elements;
* typed references.

They must not rely only on list positions or source offsets.

## Change witness

Produce:

```text
FreeChangeWitness {
  sourceDescription
  changeDescription
  applyProgram
  identityProgram
  compositionProgram
  independenceProgram
  conflictProgram
}
```

## Required derived languages

Automatically derive:

```text
DeltaMeta1
DeltaGrammar1
DeltaLambda
DeltaFoundation
```

## Laws

Check at least:

$$
\mathsf{Apply}(x,\mathsf{identity})=x
$$

$$
\mathsf{Apply}(
\mathsf{Apply}(x,a),
b
)
=

\mathsf{Apply}(x,\mathsf{compose}(a,b))
$$

when composition is valid.

Define independence:

$$
a\perp b
$$

for changes touching disjoint semantic identities.

Independent changes must commute.

## Acceptance tests

* generated change descriptions are deterministic;
* field edits survive source reformatting;
* keyed list edits survive reordering;
* Meta changes can add a declaration;
* Grammar changes can add syntax;
* lambda changes can edit a term;
* invalid typed paths are rejected;
* independent changes commute;
* overlapping incompatible changes form an explicit conflict.

## Result

$$
F_2 =
F_1
+
\text{generic semantic change calculus}
$$

---

# Commit 3: `repo: add causal patch graphs and branches in F3`

## Feature

Add a local Pijul-like semantic repository.

There is still no network and no blockchain.

## Meta change

Define:

```text
Patch
PatchDependency
PatchEffect
PatchContext
PatchConflict
PatchResolution
Branch
BranchHead
Repository
RepositoryReplay
```

A patch contains semantic changes and causal dependencies.

A branch is identified by a frontier or head set over the patch graph, not by a copied snapshot.

## Grammar change

Add repository commands and canonical syntax for:

```text
branch
record
apply
unapply
merge
resolve
log
diff
heads
dependencies
```

## Pijul-like behavior

Implement:

* patch identities independent of mutable branch names;
* causal dependency graphs;
* deterministic replay;
* commuting independent patches;
* explicit conflicts;
* conflict resolutions as patches;
* branch creation from any repository state;
* local unrecord by changing branch membership rather than deleting history;
* semantic changes over language, code and data through the same mechanism.

A patch must not blindly declare its resulting snapshot.

The result must be derived by replay.

## Repository invariants

For a fixed patch closure and branch frontier:

$$
\mathsf{Replay}(R,h)=S
$$

is deterministic.

Independent patch order must not affect the final state.

Conflicts must be represented as canonical artifacts, not thrown as host exceptions.

## Acceptance tests

* create two local branches;
* apply independent lambda edits in different orders;
* obtain identical state;
* create a genuine overlapping conflict;
* resolve it with a resolution patch;
* replay the branch from an empty CAS plus closure;
* edit Meta and Grammar on a branch;
* reconstruct a successor foundation from that branch.

## Result

$$
F_3 =
F_2
+
\text{local causal semantic repository}
$$

---

# Commit 4: `tooling: derive language toolchains from schemas in F4`

## Feature

Add schemas that derive language tooling from declarations rather than handwritten application code.

## Meta change

Define:

```text
LanguageSchema
JudgmentRole
InterpreterSchema
CompilerSchema
FormatterSchema
DiagnosticSchema
IndexSchema
ToolchainSchema
```

A language identifies which judgments play roles such as:

```text
parse
print
typecheck
evaluate
normalize
compile
executeTarget
format
index
rename
validateChange
```

## Grammar change

Add declarations such as:

```text
language
entry judgment
typechecker
interpreter
compiler source -> target
formatter
diagnostic
index
rename
projection
```

## Derived tools

From `LanguageSchema + Grammar + ToolchainSchema`, derive canonical artifacts for:

* parser;
* printer;
* formatter;
* interpreter entry point;
* compiler entry point;
* target executor;
* diagnostics;
* symbol index;
* rename operation;
* structural change editor;
* source-to-AST maps;
* AST-to-source edits;
* regression corpus.

The generated tools may be generic interpreters over schemas.

They must not be generated Scala feature classes.

## Compiler model

A compiler is a declared judgment:

$$
\mathsf{Compile}_{S,T}:
S\to T
$$

with optional evidence:

$$
\mathsf{TranslationPreservesMeaning}
$$

Define a tiny target language, such as a stack machine, entirely as another language artifact.

Compile lambda terms to that target.

## Acceptance tests

* parser/printer generated from grammar;
* lambda evaluator derived from the selected judgment;
* lambda-to-stack compiler derived from compiler schema;
* compiled execution agrees with direct evaluation;
* symbol index and rename are generated;
* changing grammar regenerates affected tools;
* tooling identities depend only on schemas and selected programs;
* no lambda-specific compiler or editor exists in Scala.

## Result

$$
F_4 =
F_3
+
\text{schema-derived language toolchains}
$$

---

# Commit 5: `governance: add claims proofs and acceptance in F5`

## Feature

Make repository changes constitutionally accepted rather than merely well-formed.

## Meta change

Define:

```text
Identity
Claim
Evidence
ProofGoal
ProofTerm
TestEvidence
Authority
AuthorityScope
AcceptanceConstitution
AcceptanceDecision
ResourceProfile
```

Define judgments:

```text
CheckClaim
CheckProof
CheckAuthority
EvaluateAcceptance
```

## Grammar change

Add syntax for:

```text
claim
proof
test
authority
scope
constitution
accept when
reject when
budget
requires
```

## Cryptography boundary

Add generic native signature primitives through capability handlers.

Native code verifies cryptographic equations.

Meta determines:

* authority;
* scope;
* subject binding;
* expiry;
* acceptable evidence;
* required tests;
* decision procedure;
* resource limits.

## Initial constitutions

Provide:

1. a local single-author constitution;
2. a test-requiring constitution;
3. a proof-requiring constitution;
4. a constitution governing Meta and Grammar changes more strictly than ordinary program changes.

## Repository integration

A branch may contain proposed but unaccepted patches.

Publication and promoted foundation construction require accepted patches.

Acceptance evidence binds:

* exact patch digest;
* exact predecessor;
* exact constitution;
* exact authority;
* exact proof or test evidence.

## Acceptance tests

Reject:

* signature by the wrong identity;
* correct signature over the wrong patch;
* evidence for another claim;
* expired authority;
* insufficient resource budget;
* kind-only acceptance shortcuts;
* Meta change accepted under an ordinary code constitution.

## Result

$$
F_5 =
F_4
+
\text{constitution-relative governance}
$$

---

# Commit 6: `foundation: package self-hosted applications and successors in F6`

## Feature

Make complete systems ordinary applications and foundations.

This commit closes the first full self-hosting staircase step.

## Meta change

Define:

```text
ApplicationManifest
FoundationManifest
MachineProfile
RuntimeProfile
ResourceProfile
EvidenceProfile
CompatibilityWitness
FoundationChange
SuccessorFoundation
```

## Grammar change

Add canonical manifest syntax for:

```text
application
foundation
machine
runtime
resource profile
evidence profile
predecessor
successor
entry
capability route
```

## Application identity

An application selects:

* Meta language;
* Grammar language;
* object languages;
* semantic programs;
* machine profile;
* runtimes;
* entry judgments;
* Studio profiles;
* dependencies.

## Foundation identity

A foundation selects:

* bootstrap identity;
* system application;
* Kernel constitution;
* acceptance constitution;
* repository root;
* resource profile;
* evidence profile;
* predecessor foundation;
* governed foundation change.

## Verification interface

A host verifies a foundation from:

```text
foundation digest
+
closure
```

It must not accept host-supplied semantic overrides.

## Independent reconstruction

Implement an independent Rust bootstrap host for:

* Canon;
* artifacts;
* closure traversal;
* MetaMachine0;
* GrammarMachine0;
* foundation verification;
* canonical verdict emission.

Define the fixed bootstrap relation in Lean and prove determinism of the core derivation model.

Require:

$$
\mathsf{Canon}
(
\mathsf{ScalaVerify}(F_6)
)
=

\mathsf{Canon}
(
\mathsf{RustVerify}(F_6)
)
$$

## Acceptance tests

* build (F_6) from (F_5) through a canonical foundation change;
* terminate the construction process;
* reconstruct (F_6) in fresh Scala and Rust processes;
* compare verdict and evidence bytes;
* verify no legacy in-memory fixture crosses the boundary;
* verify the application is loaded from one root digest;
* verify predecessor compatibility.

## Result

$$
F_6 =
F_5
+
\text{self-hosted application and foundation protocol}
$$

---

# Commit 7: `ledger: add signed publication blockchain in F7`

## Feature

Add a signed append-only chain for publication and foundation history.

Do not add networking or BFT yet.

The initial chain may have one designated authority.

## Meta change

Define:

```text
Transaction
Block
ChainState
ChainPosition
Publication
PublishedRepository
PublishedApplication
PublishedFoundation
ChainReplay
ChainValidity
```

## Grammar change

Add syntax for:

```text
transaction
publish
block
chain
checkpoint
supersedes
references
```

## Chain model

A chain block contains:

* predecessor block;
* ordered transactions;
* publication roots;
* state transition;
* author identity;
* signature;
* resource evidence.

A publication may anchor:

* a repository branch frontier;
* an application root;
* a foundation root;
* a language package;
* a data package;
* an archive root.

The chain must not contain all local development activity.

It anchors accepted publication events.

## Chain and repository relationship

A developer can:

1. choose a finalized chain publication;
2. create a local repository branch from its repository root;
3. develop offline;
4. produce accepted patches;
5. package a proposed publication;
6. append it to the chain under the current single-authority constitution.

## Acceptance tests

* replay chain from genesis;
* reject broken predecessor links;
* reject invalid transaction signatures;
* reject publication of an unaccepted repository head;
* branch a local repository from a chain checkpoint;
* publish a new language and program version;
* reconstruct the published state from chain plus closure.

## Result

$$
F_7 =
F_6
+
\text{signed publication blockchain}
$$

---

# Commit 8: `sync: add closure and branch synchronization in F8`

## Feature

Add peer-to-peer synchronization without Byzantine finality.

## Meta change

Define:

```text
PeerIdentity
SyncRequest
SyncResponse
ClosureOffer
ClosureNeed
BranchAdvertisement
ChainAdvertisement
FetchPlan
SyncTranscript
SyncValidity
```

## Grammar change

Add syntax for:

```text
peer
connect
fetch
pull
push
advertise
need
offer
sync
resume
```

## Native boundary

Add only generic transport capabilities:

```text
Send(peer, bytes)
Receive(channel)
OpenConnection(peer)
CloseConnection(peer)
```

All message schemas, negotiation, closure selection and validation remain Meta-level features.

## Synchronization behavior

Implement:

* digest-addressed closure exchange;
* missing-object discovery;
* resumable transfer;
* digest verification on receipt;
* branch-frontier exchange;
* chain-head exchange;
* local branch push and pull;
* application and foundation closure fetch;
* no implicit remote trust;
* no automatic acceptance of remote patches.

## Offline workflow

A local repository must remain usable without a network.

Synchronization updates available closures and branch frontiers.

It does not replace local semantic replay.

## Acceptance tests

* synchronize two peers from disjoint partial closures;
* resume interrupted closure transfer;
* reject corrupted artifact bytes;
* exchange two diverged branches;
* preserve explicit conflicts;
* fetch a chain publication and create a local branch;
* publish a local proposal to the designated chain authority.

## Result

$$
F_8 =
F_7
+
\text{distributed closure and branch synchronization}
$$

---

# Commit 9: `federation: add BFT finality and equivocation evidence in F9`

## Feature

Replace the single publication authority with constituted Byzantine federation.

## Meta change

Define:

```text
Replica
ReplicaSet
FederationState
Proposal
Vote
Commit
FinalityCertificate
View
ViewChange
FederationTransition
EquivocationEvidence
FinalizedHistory
```

Define judgments:

```text
ValidateProposal
ValidateVote
ValidateCertificate
ValidateTransition
ReplayFinalizedHistory
DetectEquivocation
```

## Grammar change

Add syntax for:

```text
federation
replica
proposal
vote
commit
certificate
quorum
view
view change
equivocation
finalize
```

## BFT model

Support:

* constituted replica sets;
* classic (3f+1) membership;
* quorum certificates;
* proposal digest binding;
* predecessor and successor binding;
* federation identity;
* view numbers;
* membership changes as governed transitions;
* equivocation evidence;
* deterministic replay of a fixed finalized history.

The ordering rule is:

$$
\boxed{
\mathsf{SemanticValidity}
\text{ before }
\mathsf{Agreement}
}
$$

A replica votes only after the proposed chain or foundation transition is valid under the selected Stratum foundation.

## Native boundary

Native code may provide:

* authenticated transport;
* signature primitives;
* durable message storage.

The BFT state machine, certificate meaning and transition validity remain Meta programs.

## Proof obligations

Model in Lean:

* deterministic replay of a fixed finalized history;
* no two finalized successors for the same slot under the selected quorum assumptions;
* exact proposal/certificate binding.

## Acceptance tests

* finalize two consecutive publication transitions;
* reconstruct identical chain state on independent replicas;
* reject a certificate for another proposal;
* reject insufficient quorum;
* detect double voting;
* reject wrong federation identity;
* change replica membership through a finalized governed transition;
* compare Scala and Rust finalized-history verdicts.

## Result

$$
F_9 =
F_8
+
\text{Byzantine federation and finality}
$$

---

# Commit 10: `retention: add semantic archives and reconstructible GC in F10`

## Feature

Make retention a constituted semantic choice rather than an implementation accident.

## Meta change

Define:

```text
RetentionConstitution
RetentionMode
ArchiveManifest
Checkpoint
ArchiveAttestation
GcPlan
RetainedClosure
ReconstructionTarget
```

## Grammar change

Add syntax for:

```text
retain
archive
checkpoint
collect
preserve
attest
reconstruct
```

## Retention modes

Provide at least:

```text
CurrentStateOnly
TransitionMetadata
ReplayableFoundation
FullSemanticHistory
CheckpointedHistory
```

A retention constitution explicitly identifies which judgments must remain derivable.

For example:

$$
\Sigma\vdash F\Downarrow S
$$

and:

$$
\mathsf{Retain}_{\rho}(\Sigma)=\Sigma'
$$

must imply:

$$
\Sigma'\vdash F\Downarrow S
$$

for `ReplayableFoundation`.

## Chain and repository retention

Support different policies for:

* finalized chain history;
* local repository branches;
* abandoned patch closures;
* language source;
* compiler outputs;
* Studio caches;
* foreign projections;
* proof evidence.

Derived and cached artifacts may be discarded when their regeneration inputs remain retained.

## Acceptance tests

* collect a nontrivial closure;
* preserve foundation reconstruction;
* preserve finalized-history replay;
* reject a retention plan that removes required evidence;
* regenerate parsers, printers and indexes after cache deletion;
* reconstruct an application from a checkpoint plus later patches;
* verify archived state in Scala and Rust.

## Result

$$
F_{10} =
F_9
+
\text{constituted semantic retention and archives}
$$

---

# Commit 11: `studio: derive profile-guided IDEs and close the chain-workspace loop in F11`

## Feature

Add schema-guided editors and complete the full chain-to-local-to-chain development cycle.

This commit must prove that generated editors do not all share one generic panel layout.

## 11.1 Separate three schemas

### LanguageSchema

Defines meaning:

```text
sorts
constructors
field identities
keys
judgments
types
evaluation
compilation
changes
validation
projections
```

### ToolchainSchema

Defines tool roles:

```text
parser
printer
formatter
typechecker
interpreter
compiler
executor
indexer
rename
diagnostics
tests
proof obligations
```

### StudioProfile

Defines interaction:

```text
views
panels
navigation
editors
commands
workflows
roles
layout hints
selection relationships
inspectors
previews
conflict views
approval views
```

A StudioProfile must not alter language semantics.

## Meta change

Define:

```text
StudioProfile
ViewSchema
PanelSchema
EditorSchema
NavigationSchema
WorkflowSchema
RoleSchema
ActionSchema
ProjectionSchema
ForeignSurface
```

Define derivation judgments:

```text
DeriveEditor
DeriveNavigation
DeriveActions
DeriveDiagnostics
DeriveConflictEditor
DeriveRepositoryBrowser
DeriveForeignProjection
```

## Grammar change

Add profile syntax such as:

```text
studio
view
panel
tree
browser
editor
inspector
preview
workflow
role
action
show when
group by
navigate through
```

## Generated Studio runtime

Build one generic Studio runtime that consumes schemas.

Do not generate one Scala UI class per application.

The runtime may provide generic primitives such as:

* tree;
* list;
* table;
* text editor;
* structured form;
* graph;
* inspector;
* diff;
* conflict resolver;
* preview;
* workflow queue;
* command palette.

Profiles compose these primitives differently.

## Smalltalk-style profile

Create a Smalltalk-like language and profile with:

* package or category browser;
* class list;
* method protocol list;
* method editor;
* workspace;
* object inspector;
* transcript;
* debugger-oriented evaluation view;
* local branch and change browser.

The primary navigation model is code-centric.

## SDS profile

Create an SDS language and profile with:

* section tree;
* paragraph and regulated-sentence editor;
* multilingual sentence variants;
* typed data fields;
* physical units;
* linked values;
* shadow overrides;
* mixture components addressed by stable keys;
* validation findings;
* approval workflow;
* change provenance;
* PDF preview;
* JSON/XML import and export;
* spreadsheet projection where declared.

The primary navigation model is document and workflow-centric.

The Smalltalk and SDS profiles must visibly differ in:

* panel arrangement;
* navigation;
* available commands;
* workflow;
* field editors;
* preview surfaces.

They share the schema-driven Studio runtime, not the same fixed interface.

## Complete chain-to-workspace round trip

Demonstrate:

1. a finalized chain publishes a Stratum foundation and application;
2. a developer fetches the publication closure;
3. the developer creates a local Pijul-like branch;
4. tooling is derived from the selected schemas;
5. the developer changes a language;
6. the developer changes code in that language;
7. the developer changes domain data;
8. changes are recorded as semantic patches;
9. independent patches commute;
10. conflicts become explicit artifacts;
11. tests, proofs and acceptance run locally;
12. a publication proposal is packaged;
13. peers fetch its closure;
14. federation validates and finalizes it;
15. the chain advances;
16. a fresh node reconstructs the new foundation, application, repository and data;
17. generated Smalltalk and SDS Studios reopen the reconstructed state.

## Final parity gate

Require:

$$
\mathsf{Canon}
(
\mathsf{ScalaReconstruct}(F_{11})
)
=

\mathsf{Canon}
(
\mathsf{RustReconstruct}(F_{11})
)
$$

Require archive reconstruction after retention.

Require the complete finalized publication history to replay deterministically.

## Result

$$
F_{11} =
F_{10}
+
\text{profile-guided Studios and complete publication workflow}
$$

---

# 6. Final architecture

At the end, the architecture must be:

```text
Tiny native bootstrap host
  ├── Canon
  ├── Artifact/CAS
  ├── MetaMachine0
  ├── GrammarMachine0
  ├── Crypto capabilities
  ├── Network capabilities
  └── Filesystem/process capabilities

Stratum foundation
  ├── evolving Meta language
  ├── evolving Grammar language
  ├── generic FreeChange
  ├── Pijul-like semantic repository
  ├── language/tool schemas
  ├── generated interpreters and compilers
  ├── governance and proofs
  ├── applications and foundations
  ├── publication blockchain
  ├── synchronization
  ├── BFT federation
  ├── retention constitutions
  └── schema-guided Studio profiles

Applications
  ├── Smalltalk-style development environment
  ├── SDS editor and publication workflow
  └── future domain systems
```

---

# 7. Recommended repository structure

```text
stratum/
  host-scala/
    canon/
    artifact/
    cas/
    meta-machine0/
    grammar-machine0/
    capabilities/

  host-rust/
    canon/
    artifact/
    cas/
    meta-machine0/
    grammar-machine0/
    verifier/

  verifier-lean/
    Canon/
    Meta0/
    Grammar0/
    Change/
    Repository/
    Federation/
    Retention/

  languages/
    meta/
    grammar/
    lambda/
    stack/
    smalltalk/
    sds/

  features/
    changes/
    repository/
    tooling/
    governance/
    foundation/
    ledger/
    sync/
    federation/
    retention/
    studio/

  profiles/
    smalltalk/
    sds/

  foundations/
    F0/
    F1/
    F2/
    F3/
    F4/
    F5/
    F6/
    F7/
    F8/
    F9/
    F10/
    F11/

  changes/
    F0-F1/
    F1-F2/
    F2-F3/
    F3-F4/
    F4-F5/
    F5-F6/
    F6-F7/
    F7-F8/
    F8-F9/
    F9-F10/
    F10-F11/

  fixtures/
    lambda/
    repository/
    chain/
    federation/
    archive/
    smalltalk/
    sds/

  docs/
    foundations/
    invariants.md
    staircase.md
    native-boundary.md
    publication-workflow.md
```

---

# 8. CI requirements

Create a staircase CI workflow that runs every foundation in order.

For every (F_n):

1. reconstruct (F_{n-1});
2. load the canonical change;
3. construct (F_n);
4. verify the successor with (F_{n-1});
5. start a clean process;
6. reconstruct (F_n);
7. compare its digest with the committed golden digest;
8. rerun every earlier foundation fixture.

From (F_6) onward:

* run Scala reconstruction;
* run Rust reconstruction;
* compare canonical verdict bytes;
* compile the Lean model and required theorems.

The workflow must fail if:

* a foundation artifact is a placeholder;
* a digest drifts;
* a closure is incomplete;
* an earlier foundation cannot be reconstructed;
* a feature requires an unconstituted host default;
* Scala and Rust evidence differs;
* a required verifier is unavailable;
* a test is skipped;
* feature-specific semantic code appears in the native host.

---

# 9. Final completion criteria

Stratum is complete at (F_{11}) only when all of the following hold.

## Minimal root

The native host remains a small generic interpreter and capability shell.

## Semantic staircase

Each foundation was produced by one canonical change from its predecessor.

## Meta evolution

Every added feature is expressible through an evolved Meta language.

## Grammar evolution

Every added surface is expressible through an evolved Grammar language.

## Generic changes

Every language and system artifact has a change language.

## Pijul-like repository

Local repositories support:

* causal patches;
* branches;
* independent commutation;
* explicit conflicts;
* semantic replay.

## Blockchain publication

Accepted repository, application and foundation roots can be published to a finalized chain.

## Local development

A finalized publication can be branched into an offline local repository.

## Return publication

Local language, code and data changes can be accepted and published back to the chain.

## Independent reconstruction

Fresh Scala and Rust hosts reconstruct the same finalized result.

## Retention

A retained archive reconstructs the selected foundation and application.

## Generated tools

Parsers, printers, interpreters, compilers, diagnostics, indexes and editors are derived from schemas and selected Meta programs.

## Distinct Studios

The Smalltalk IDE and SDS editor are generated from distinct Studio profiles and do not collapse into one generic fixed-panel interface.

The final statement is:

$$
\boxed{
\begin{aligned}
&F_0
\xrightarrow{\delta_0}
F_1
\xrightarrow{\delta_1}
\cdots
\xrightarrow{\delta_{10}}
F_{11}\
&\mathsf{Invariants}(F_n)
\quad\text{for every }0\leq n\leq11\
&\mathsf{ChainPublication}
\to
\mathsf{LocalBranch}
\to
\mathsf{SemanticDevelopment}
\to
\mathsf{AcceptedPublication}
\to
\mathsf{FinalizedChain}\
&\mathsf{ScalaReconstruct}(F_{11})
==================================

\mathsf{RustReconstruct}(F_{11})
\end{aligned}
}
$$

Build the foundations in order.

Do not implement later features early.

Do not combine foundation commits.

Do not move feature semantics into Scala because it is convenient.

The proof of Stratum is not that the final system contains many features.

The proof is that every feature was added by the system immediately beneath it.
