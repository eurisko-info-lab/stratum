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
A match with the committed `digest.txt` is evidence that $F_n$ genuinely builds
$F_{n+1}$. Enforced for every step by [tools/staircase.sh](../tools/staircase.sh).

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

## Semantic and surface separation

A language's meaning and its presentation are separate artifacts. Changing a
panel layout must not change a language's semantic digest.
