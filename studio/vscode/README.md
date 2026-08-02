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

`stratum.world` selects the world the server answers from, and
`stratum.server` the command that starts it. The client is a plain LSP client,
so it works in VS Code and in forks such as Cursor.

See [docs/studio.md](../../docs/studio.md).
