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

## Semantic and surface separation

A language's meaning and its presentation are separate artifacts. Changing a
panel layout must not change a language's semantic digest.
