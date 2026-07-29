# The native boundary

`StratumHost0` is the only native semantic code in Stratum. It is frozen after
$F_1$.

## Permanent responsibilities

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

## What the host must never know

Lambda calculus, types, repositories, patches, compilers, acceptance policies,
publication ledgers, synchronization, agreement protocols, retention, document
languages, development environments and editor panels are all feature artifacts.

## The asymmetry

```text
Native host:        program, goal, closure, constitution, budget, capabilities
Feature artifacts:  everything else
```

## MetaMachine0

A total, deterministic interpreter over canonical values. It provides algebraic
data, records, sums, products, lists, maps, pattern matching, structural
recursion, bounded evaluation through an explicit budget, canonical evidence and
explicit capability requests.

It excludes unrestricted recursion (every derivation is budget-bounded),
mutation, exceptions as control flow, hidden I/O, host reflection, native
feature plugins, foreign calls, higher-order unification and nondeterministic
search. Rule order is the declared order of `match` cases.

$$
\mathsf{derive}(P,\Sigma,K,B,G) = V
$$

## GrammarMachine0

Interprets canonical grammar artifacts: token declarations, lexical classes,
recursive categories, constructors, field labels, precedence through category
layering, associativity through `fold`, parsing and canonical printing. It
contains no syntax for any particular language.

## Capability dispatch

The host performs operations; Meta programs decide what they mean. The host may
answer `verify(key, message, signature)`, but it never decides whether a signer
is entitled to act, whether a change is accepted, whether agreement is
sufficient, or whether a transition has settled.

## Permitted native changes after F1

- correctness fixes in the fixed bootstrap implementation
- semantics-neutral performance improvements
- new generic capability adapters
- additional independent implementations of the same bootstrap interface
- improved diagnostics that do not affect canonical verdicts

## Enforcement

[test-scala/NativeBoundarySuite.scala](../test-scala/NativeBoundarySuite.scala)
rejects feature vocabulary in host sources, rejects semantic dispatch on feature
tags outside the fixed bootstrap vocabulary, and rejects networking or
persistence libraries.
