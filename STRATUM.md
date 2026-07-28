# Stratum Charter

Stratum is the first canonical Cairn application graph that must be packaged,
verified, archived, and reconstructed through Cairn's public bootstrap
interfaces.

## Boundary rules

- Stratum is a sibling repository of Cairn, not a Cairn subproject.
- Dependency direction is one-way: `Stratum -> Cairn Bootstrap Protocol`.
- Stratum must not import Cairn internal runtime packages, legacy validation
  facades, package-private classes, or fixture directories.
- All semantic authority flows through canonical bytes, digests, closures, and
  versioned commands.

## Initial repository goals

1. Establish the repository shape required for genesis.
2. Pin an immutable Cairn dependency in `stratum.lock`.
3. Reserve the canonical artifact paths for genesis and the first successor.
4. Add architectural checks that protect the repository boundary.

## Deferred work

Before `F1 -> F2`, this repository does not expand into domain-product
development, plugin systems, broad language packs, or performance shortcuts
that bypass CKC semantics.
