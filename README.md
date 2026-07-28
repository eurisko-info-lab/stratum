# Stratum

Stratum is the first Cairn system constructed as an application of Cairn
itself. This repository is the canonical sibling repository for that effort:
Stratum depends on a pinned Cairn bootstrap surface, while Cairn does not
depend on Stratum internals.

## Repository contract

- Stratum consumes the public **Cairn Bootstrap Protocol v1** surface only.
- The Cairn dependency is pinned in `/home/runner/work/stratum/stratum/stratum.lock`.
- Stratum source must not import Cairn internal runtime packages or rely on
  Cairn source-tree paths, package-private APIs, or mutable fixture state.
- Authoritative Stratum semantics belong in canonical artifacts committed in
  this repository, not in host-language service implementations.

## Current scaffold

This repository starts with the structure required by the Stratum genesis plan:

```text
bootstrap/       Cairn protocol boundary and compatibility notes
languages/       Meta, grammar, description, and foundation artifact packages
programs/        CKC semantic program families
genesis/         genesis application/foundation placeholders and fixtures
successor/       first governed successor placeholders
verifier-tests/  repository contract checks and host-specific test stubs
tools/           packaging and inspection tool entry points
docs/            architecture, bootstrap, genesis, and staircase notes
```

The committed `.canon` files are reserved placeholders that mark the required
artifact paths until the locked Cairn bootstrap release publishes the canonical
bytes they must contain.

## Locked Cairn dependency

The initial lockfile pins Stratum to the current immutable Cairn commit:

- repository: `eurisko-info-lab/cairn`
- commit: `2a943f50bc093db704f97b6a3c1bf212b1cb7dca`

Artifact digests stay explicitly tracked in the same lockfile so the repository
can fail closed once the bootstrap release exports them.

## Validation

Run the repository contract checks with:

```bash
cd /home/runner/work/stratum/stratum
python -m unittest discover -s verifier-tests -p 'test_*.py'
```

These checks verify the scaffold, the lockfile contract, and the absence of
forbidden Cairn internal imports in source files.
