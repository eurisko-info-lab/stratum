# Bootstrap Contract

Stratum consumes a narrow, versioned Cairn bootstrap surface named
**Cairn Bootstrap Protocol v1**.

## Frozen protocol areas

- canonical value grammar and byte encoding;
- artifact envelope, digest identity, and dependency enumeration;
- deterministic MetaMachine0 program execution and evidence construction;
- foundation verification through public host commands;
- bootstrap identity fields that bind protocol, canon, artifact, machine,
  evidence, and crypto versions.

## Expected Cairn commands

The public host interface is expected to expose:

```text
cairn verify-application
cairn verify-foundation
cairn derive
cairn export-closure
cairn import-closure
```

Stratum binds to that surface through `stratum.lock` and does not assume access
to internal Scala or Rust implementation packages.
