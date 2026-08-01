# Migration from Git to Stratum

This document defines the recovery plan for migrating this repository from
Git into a genuinely semantic Stratum repository.

The language packages introduced by Git commit `17ca441` are not sufficient:
most of them wrap an entire source file in a language-named node such as
`ScalaFile(text)` or `RustFile(text)`. That is plain text with a different
label, not a real AST. The commit and the Stratum frontiers derived from it
must therefore be treated as failed migration work rather than as a valid
semantic repository.

## 1. Restore honest foundations

- Bind `.meta` files directly to the existing Meta grammar and Meta AST.
- Bind `.grammar` files directly to the existing Grammar grammar and AST.
- Retain the existing structural SDS and Smalltalk language definitions
  without replacing or duplicating them.
- Use `TextFile(text)` only for genuinely opaque plain-text files such as
  `.txt`, `LICENSE`, and toolchain marker files.
- Do not classify a file as structured merely because its extension maps to a
  language-named wrapper.

## 2. Define the exact migration target

Inventory every file included by repository policy on:

- `main`
- `featured/sds`
- `featured/smalltalk`
- `featured/vscode`

Record the language and applicable language version for every path. The
initial acceptance set is the files present on those branches, not an
unsupported claim of complete coverage for every historical language version.

Build outputs and dependency caches remain excluded according to repository
policy, including `target`, `.lake`, `node_modules`, `.bloop`, `.bsp`,
`.metals`, and equivalent generated directories.

## 3. Implement structural language packages

Implement the remaining languages in increasing order of grammar complexity:

1. JSON and properties
2. TOML and YAML
3. HTML and SVG
4. Markdown and transcripts
5. Shell
6. TypeScript and JavaScript
7. Lean
8. Rust
9. Scala 3

Every language package must be defined by artifacts under `languages/` and
must contain:

- a structural grammar;
- a typed Meta AST;
- generated Grammar and Meta artifacts;
- parse and print judgments;
- well-formedness judgments;
- positive repository examples;
- malformed negative fixtures.

ASTs must represent language constructs such as declarations, expressions,
types, patterns, members, blocks, keys, values, elements, and attributes as
appropriate for the language. A single node containing the complete source
text is not acceptable.

No Scala- or Rust-specific parsing or printing logic may be added to native
Scala or Rust host code. Meta and Grammar remain the only native bootstrap
languages.

## 4. Add anti-cheating acceptance tests

For every declared non-text language:

- parse every repository file assigned to that language;
- print the resulting tree and parse the printed form again;
- verify that the result contains language-specific structural nodes;
- reject at least one deliberately malformed fixture;
- reject ASTs whose root contains the complete source as one string;
- reject grammars with a catch-all token capable of consuming arbitrary
  source as one token;
- verify that the source and generated artifacts agree;
- verify Scala and Rust host parity for the derivations.

A language is not complete until all of these checks pass.

## 5. Rebuild the language catalogue

Regenerate `languages/catalogue.generated.meta` and
`languages/catalogue.canon` exclusively from the accepted language packages.

Catalogue entries may reference only real Grammar and Meta artifacts.
Deliberately invalid source files must be classified explicitly as negative
fixtures rather than silently accepted by a permissive grammar.

Unknown formats must fail repository recording with the exact unclassified
path.

## 6. Validate every branch before publication

For each target Git branch:

1. regenerate all checked-in artifacts;
2. verify a clean generated-artifact diff;
3. run host and parity tests;
4. run every language parse/print and negative-fixture test;
5. run repository tests;
6. import the complete working tree into an isolated Stratum frontier;
7. verify the complete object closure;
8. confirm that Stratum status is clean.

No branch is published until every gate succeeds.

## 7. Replace the failed history

After all language packages pass:

- commit the corrected implementation on `main`;
- push `main`;
- rebase every `featured/*` Git branch onto the corrected `main`;
- push rewritten feature histories with `--force-with-lease`;
- retain the current Stratum frontiers under an explicitly named
  `failed-migration` or equivalent backup namespace;
- recreate Stratum `main` from the last valid pre-migration frontier;
- record the corrected semantic tree;
- rebase each Stratum feature frontier onto corrected Stratum `main`;
- record each feature's corrected semantic tree;
- verify all active Stratum closures and working-tree statuses.

Old immutable objects remain recoverable, but failed frontiers must not remain
the active branch heads.

## Definition of done

The Git-to-Stratum migration is complete only when:

- every included non-text file has a real structural AST;
- every included file parses under its declared grammar;
- every parsed file passes print/reparse validation;
- malformed fixtures are rejected or explicitly classified as negative
  fixtures;
- no language relies on a whole-file catch-all wrapper;
- native host code contains no language-specific parser;
- all tests pass on `main` and every target feature branch;
- all active Stratum frontiers verify;
- every corresponding Stratum working tree reports clean;
- Git and Stratum branch histories are committed, rebased, and published.

Until all conditions are met, the migration must be reported as incomplete.
