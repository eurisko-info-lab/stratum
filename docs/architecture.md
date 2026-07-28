# Architecture

Stratum is a sibling repository of Cairn:

```text
eurisko-info-lab/
  cairn/
  stratum/
```

The dependency direction is intentionally one-way:

```text
Stratum -> Cairn Bootstrap Protocol v1
```

and never:

```text
Stratum -> Cairn implementation internals
```

This repository therefore treats Cairn as a versioned bootstrap substrate that
provides Canon, typed artifacts, digest identity, CAS access, signature
primitives, capability requests, MetaMachine0, application loading, foundation
verification, and canonical verdict encoding.

## Hidden-coupling guardrails

Stratum source must not:

- import Cairn internal runtime packages;
- use Cairn package-private classes or legacy validation facades;
- read Cairn fixture directories directly;
- depend on Cairn source-tree paths;
- share in-memory artifact objects across process boundaries;
- accept semantic overrides outside canonical artifacts and explicit commands.

The repository contract test under
`/home/runner/work/stratum/stratum/verifier-tests/test_repository_contract.py`
enforces the scaffold and guards against forbidden source imports.
