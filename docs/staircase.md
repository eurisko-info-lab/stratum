# The staircase

Each commit constructs exactly one new foundation from the one beneath it:

$$
F_n \xrightarrow{\ \delta_n\ :\ \Delta F_n\ } F_{n+1}
\qquad
\delta_n = (\delta_n^{Meta}, \delta_n^{Grammar}, \delta_n^{Feature}, \delta_n^{Foundation})
$$

and the predecessor validates the successor:

$$
F_n \vdash \mathsf{Build}(F_n, \delta_n) \Downarrow F_{n+1}
$$

## Steps

| Step | Commit | Adds |
| --- | --- | --- |
| F0 | `seed` | canonical local lambda world |
| F1 | `meta` | self-described Meta and Grammar |
| F2 | `changes` | generic semantic change calculus |
| F3 | `repo` | local causal semantic repository |
| F4 | `tooling` | schema-derived language toolchains |
| F5 | `governance` | constitution-relative governance |
| F6 | `foundation` | self-hosted application and foundation protocol |
| F7 | `ledger` | signed publication ledger |
| F8 | `sync` | distributed closure and branch synchronization |
| F9 | `federation` | constituted agreement and settlement |
| F10 | `retention` | constituted semantic retention and archives |
| F11 | `studio` | profile-guided studios and the complete publication workflow |

## Protocol for step `n`

1. branch from the exact promoted $F_{n-1}$
2. define `delta-meta`
3. define `delta-grammar`
4. define the new feature artifacts
5. define or derive their change languages
6. apply the changes using $F_{n-1}$
7. construct $F_n$
8. verify $F_n$ using $F_{n-1}$
9. start a fresh process
10. reconstruct $F_n$ from its digest and closure
11. run all earlier foundation transcripts
12. commit the resulting canonical artifacts
13. tag the commit `foundation/Fn`

## Artifacts per step

```text
foundations/Fn/foundation.canon
foundations/Fn/application.canon
foundations/Fn/closure/
foundations/Fn/verdict.canon
foundations/Fn/evidence.canon
foundations/Fn/digest.txt
changes/F(n-1)-Fn/derivation.canon

changes/F(n-1)-Fn/change.canon
changes/F(n-1)-Fn/delta-meta.canon
changes/F(n-1)-Fn/delta-grammar.canon

docs/foundations/Fn.md
```

The Git commit records the implementation event. The canonical foundation change
is the authoritative semantic event.

## Commands

```bash
stratum foundation build --spec foundations/Fn/build.canon --out foundations/Fn
stratum foundation verify --dir foundations/Fn
stratum foundation verify-successor --predecessor foundations/F(n-1) --successor foundations/Fn
stratum foundation derive-change --predecessor foundations/F(n-1) --successor foundations/Fn --out changes/F(n-1)-Fn/derivation.canon
stratum foundation derive-successor --predecessor foundations/F(n-1) --derivation changes/F(n-1)-Fn/derivation.canon --expect foundations/Fn
stratum foundation reconstruct --dir foundations/Fn
stratum transcript run fixtures
```

## Verified versus constructed

`verify-successor` asks the predecessor whether the successor is well formed.
`derive-successor` asks the predecessor to *build* it: it receives the
predecessor reference and the canonical change only, applies the change to the
predecessor's application manifest by field identity, recomputes the application
identity, assembles the successor foundation manifest and returns its digest.
The staircase requires both for every step.
