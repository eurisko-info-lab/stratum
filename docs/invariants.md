# Foundation invariants

Every foundation $F_n$ satisfies the following.

## Canonical identity

Every authoritative value has one canonical representation:

$$
\mathsf{decode}(\mathsf{encode}(x)) = x
\qquad
\mathsf{id}(x) = H(\mathsf{encode}(x))
$$

Non-canonical encodings are rejected by the decoder. Maps are stored in the
total canonical order of their keys, natural numbers use minimal varints, and
re-encoding a decoded value must reproduce the original bytes.

Enforced by [test-scala/CanonSuite.scala](../test-scala/CanonSuite.scala).

## Closure completeness

A foundation is reconstructible from `foundation digest + complete immutable
artifact closure`. No semantic result may depend on classpath discovery, mutable
registries, environment defaults, source-tree paths, process identity or
unrepresented network resources.

Enforced by `foundation reconstruct`, which loads only `digest.txt` and
`closure/`, then re-runs every check.

Closures are publication artifacts, not repository sources. A checkout obtains
them from the commit's [materialization image](images.md). Each non-root image
is advanced from the exact image of its Git parent and contributes only new
content-addressed objects. A missing parent image is an availability failure,
not permission to replay history. A published clean-room bundle still contains
the digest and full closure required by this invariant.

## Deterministic interpretation

$$
\mathsf{derive}(P,\Sigma,K,B,G) = V
$$

is a function. Identical inputs produce identical verdicts, identical evidence
and identical resource accounting. Budget exhaustion produces a canonical
verdict, never a host exception.

## Parse and print

For every canonical language artifact:

$$
\mathsf{parse}(\mathsf{print}(a)) = a
$$

Concrete source layout may differ; canonical AST identity round-trips.

## Every language has a change language

For every language $L$ there is $\Delta L$ and

$$
\mathsf{Apply}_L : L \times \Delta L \to L
$$

Before F2 this is the predecessor-bound `ReplaceChange`. From F2 onward it is
derived generically from structural descriptions.

## Explicit authority

Every accepted transition names the constitution, the proposed change, the
entitled party, the evidence, the decision, the predecessor and the successor.

## Predecessor-verified succession

$$
F_n \vdash F_{n+1}\ \mathsf{valid}
$$

realised by `foundation verify-successor`, which runs the predecessor's own
`VerifyFoundation` judgment over the successor manifest. The successor must then
reconstruct itself from its own closure.

## Predecessor-constructed succession

The stronger form. `foundation derive-successor` runs the predecessor's own
`DeriveSuccessor` judgment over the predecessor reference and the canonical
change **only**:

$$
F_n \vdash \mathsf{ApplyFoundationChange}(F_n, \delta_n) \Downarrow F_{n+1}
$$

No successor manifest is supplied. The judgment applies the field-identity
change to the predecessor's application manifest, recomputes the application
identity, assembles the successor foundation manifest and returns its digest.
A match with the tracked `digest.txt` is evidence that $F_n$ genuinely builds
$F_{n+1}$. Enforced when the commit is advanced by
[tools/advance-image.sh](../tools/advance-image.sh).

## Reproducible generation

Meta and Grammar elaborations are disposable working-tree outputs. Their exact
bytes are checked against `generated.sha256`; foundation and application
outputs are checked semantically against each world's `digest.txt`. Generated
closures, manifests, evidence, transition derivations and editor packages are
never authoritative repository inputs.

## Frozen host core

The bootstrap host publishes a canonical manifest of its fixed interface: the
canonical tags, the Meta0 expression, pattern and primitive sets, the Grammar0
forms, the evidence shape and the verdict forms. Every foundation from F1 onward
references exactly that identity, so widening the fixed calculus changes the
digest and has to be a reviewed act. Capability adapters are excluded from the
freeze and may evolve. Enforced by
[test-scala/HostCoreSuite.scala](../test-scala/HostCoreSuite.scala).

## Two independent hosts agree

$$
\mathsf{ScalaDerive}(F_n) = \mathsf{RustDerive}(F_n)
$$

[host-rust](../host-rust) implements Canon, artifacts, closures, GrammarMachine0
and MetaMachine0 from scratch. `tools/parity.sh` compares, for every foundation,
the digest of every canonical verdict including its evidence and its
deterministic resource accounting, plus the attestation and the host core
identity. It also replays an adversarial encoding corpus that both hosts must
accept and reject identically.

The claim is about **what the system concludes**, not about what it does. Two
hosts must derive the same verdict from the same closure; they need not both be
able to *perform* a run against real state. Applying a transcript is an effect,
not a judgment: it belongs to whichever host is holding the working tree, and it
is gated by rehearsal rather than by parity. What the run produces is a
canonical record, and that is back inside the claim, because it is ordinary
Canon and its digest is fixed by the encoding both hosts implement.

A reader who wants the shorter rule: if two hosts could disagree about it, it is
gated by parity. If only one host can do it at all, it is gated by a rehearsal
that proves it and keeps nothing.

## Semantic and surface separation

A language's meaning and its presentation are separate artifacts. Changing a
panel layout must not change a language's semantic digest.
