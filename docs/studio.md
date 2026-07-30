# Stratum Studio

```text
host-scala/lsp      the protocol adapter
languages/service   the generic language service
languages/pdf       the pdf projection
```

A client for a particular editor, and the manifest generator that binds it to a
world, belong with that editor rather than here.

A language server and an editor client for every language a world publishes.
Neither of them knows any language.

## The split

The freeze on the host core permits new adapters and diagnostics, and forbids
new primitives, forms and tags. That line decides the whole design:

| Side | Owns |
| --- | --- |
| the foundation | what a diagnostic is, where it points, what a symbol means, what formatting produces, what a command answers |
| the adapter | JSON, `Content-Length` framing, and turning a character offset into a line and a UTF-16 character |

So the server is a courier. Everything an editor displays is derived by a
judgment, under the world's own step budget, from artifacts in its closure, and
is reproducible like any other verdict. The independent Rust host agrees on all
of it.

The gate that keeps this honest is
[NativeBoundarySuite](../test-scala/NativeBoundarySuite.scala): the host may
not mention a feature of the system above it, nor dispatch on any of its tags.
Everything crossing the boundary is therefore a **keyed map**, never a tagged
node, and the adapter looks fields up by name.

## Positions without spans

The parse tree carries no source positions, and the host may not grow a
primitive to add them. So positions are recovered inside the foundation:

- a finding names the entry it is about, and that name occurs in the source, so
  [`TextFind`](../languages/service/service.meta) locates it;
- a syntax error is reported by the grammar machine with its offset in the
  message, so `ParseErrorOffset` reads it back.

Substring search is written in Meta1 because `index-of` searches lists, not
strings.

## What a language gets for free

| Feature | Where it comes from |
| --- | --- |
| syntax errors | the grammar machine, positioned by the service |
| highlighting | semantic tokens over the grammar's own `token` classes, on every keystroke |
| comments and brackets | the descriptor, applied with `setLanguageConfiguration` at activation |
| completion | every keyword read out of the grammar artifact |
| formatting | the round trip the platform already guarantees: parse, then print |
| outline and hover | the deployment's `ServiceSymbols` |
| views | the deployed Studio profile's panels, rendered by F11's runtime |
| commands | the profile's commands, answered by the foundation |

The first five need no code at all: a grammar is enough.

## What is generated, and what is not

Nothing an editor needs statically can be served live: a language identifier
and its file extensions are read from a client's manifest when the client is
registered, and no protocol request can add a language afterwards. So a client
generates that much from a world, once, and the generator belongs with the
client.

Everything else that an editor would normally ship as static data is served
live instead:

| Usually a generated file | Here |
| --- | --- |
| a syntax highlighting grammar | `textDocument/semanticTokens/full` |
| a language configuration file | `stratum/languages`, applied at activation |

That is not only tidier, it is the difference between colours that agree with
the parser and colours that are a copy of a grammar and can fall behind it.
The `grammar-lex` capability reports the token classes the grammar declares,
located in the buffer; the adapter only performs the protocol's delta
encoding. Capabilities are outside the frozen host core, which is what makes
this legal without touching the core's identity.

## Binding a language

A world publishes a descriptor - a map, so the host dispatches on nothing - and
defines the fixed entry points. A branch that carries an application carries
its descriptor with it, in `applications/<name>/service.canon`.

```text
ServiceDiagnostics [grammar text]        ServiceFormat [grammar text]
ServiceSymbols [grammar text]            ServiceCommand [grammar text command]
ServiceCompletions [grammar text]        ServiceViews [grammar text]
ServiceKeywords [grammar]                ServiceSemanticTokens [grammar text]
```

The generic half lives in
[languages/service/service.meta](../languages/service/service.meta) and is
included by any deployment that wants it.

Entry points must be **total over foreign documents**: one world serves five
cleanly rather than fail.

## Running it

```bash
sbt "runMain stratum.cli.Stratum lsp languages --world <world>"
sbt "runMain stratum.cli.Stratum lsp replay    --world <world> --script <script>"
```

A client binds itself to a world by generating its own manifest from what the
world publishes, so it cannot drift from the languages it edits. The generator
lives with the client.

## Testing

An editing session is a transcript like any other derivation. A branch that
carries an application carries one in `fixtures/lsp`: it opens buffers that
exist on no disk, including one of the platform's own Meta1 sources with a
syntax error, and records every byte the server sent back.
