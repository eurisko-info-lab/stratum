# Stratum Studio

The editor client for any Stratum world. It contains no language knowledge:
the languages it edits, their file extensions, the commands and the arrangement
of the views are generated into `package.json` from a world, and everything it
displays is derived by that world's own judgments.

On this branch the manifest is unbound, so the client contributes no languages
and does nothing useful on its own. Bind it to a world:

```bash
git merge featured/sds                     # or any branch carrying a world
sbt "runMain stratum.cli.Stratum lsp package --world applications/sds --out studio/vscode"
cd studio/vscode && npm install && npm run compile
```

Then open the workspace with the client loaded:

```bash
code --extensionDevelopmentPath="$PWD/studio/vscode" --new-window .
```

## Transcript Testing

```bash
cd studio/vscode/test && npm install && npm test
```

The harness has its own `package.json` because this directory's one is
generated from the world by `stratum lsp package`, and a generated file cannot
carry a hand-written test dependency.

It packages the client for each transcript world into a
temporary extension copy, launches a real VS Code extension host, replays the
transcript's editor actions, and asserts both UI state and the LSP requests the
plugin made.

The fixtures live under `test/fixtures/*.studio`. They are real Stratum source
files rather than ad hoc JSONL, so the same script can serve as both an
executable replay test and readable tutorial material. A minimal example:

```text
world "applications/sds";

writeFile {
	path: ".studio-test/acetone.sds",
	text: [
		'document "Acetone"',
		'',
		'section 1 "Identification"'
	]
};

openFile { path: ".studio-test/acetone.sds" };
assertTraceContains ["stratum/views", "stratum/documents"];
```

The runner compiles that source through the checked-in `studio` grammar, then
replays actions and assertions such as writing a file, opening it, replacing
its contents, invoking a command, checking views, checking diagnostics, or
asserting that requests such as `stratum/views`, `stratum/documents`, and
`workspace/executeCommand` were sent.

`stratum.world` selects the world the server answers from, and
`stratum.server` the command that starts it. The client is a plain LSP client,
so it works in VS Code and in forks such as Cursor.

See [docs/studio.md](../../docs/studio.md).
