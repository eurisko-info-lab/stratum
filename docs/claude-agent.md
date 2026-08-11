# Stratum Adaptive Transcript Agent

Stratum provides a persistent repository daemon, a one-tool MCP bridge, and a
local Ollama controller. A local model chooses and submits one command at a
time, observes its result, and only then chooses the next command.

## Execution model

The model returns only the next JSON action:

```json
{
  "command": "repo open project tmp/my-project",
  "expected": ["session project project tmp/my-project repository tmp/my-project/.stratum"]
}
```

Each action contains one command, optional source `input`, and an immutable list
of expected complete output lines. An empty expectation list accepts any
successful result when output is dynamic.

The daemon arms a Stratum journal for each step. It retains effects only when
the command succeeds and all expected lines occur in order. On command failure
or expectation mismatch, it rolls back the complete step and returns the actual
output. The model may vary only the current command or source input; it cannot
weaken the original expectations. After a match, that observation is returned
to the model so it can choose the next command. Exhausting the retry limit fails
the whole transcript with the command, expectations, and last observation.

## Local model

Prerequisites:

- Ollama at `http://127.0.0.1:11434`
- `qwen2.5-coder:7b-instruct-q5_K_M` (the default; its higher-fidelity Q5
  quantization and 8,192-token context fit fully on an 8 GB GPU)

Run a task:

```sh
python3 tools/stratum_local_agent.py \
  --model qwen2.5-coder:7b-instruct-q5_K_M \
  "Open the existing Stratum repository at tmp as session project, show status, verify it, and show its log."
```

The controller sends `keep_alive: -1` to Ollama by default, keeping the selected
model resident between command-selection and repair calls. Use `--keep-alive <seconds>`
to opt into eviction after an idle interval. GPU selection belongs to the
`ollama serve` process; on a hybrid AMD/NVIDIA machine, launch that service with
the NVIDIA device visible to keep inference off the integrated GPU.
Temperature is zero because each short JSON action is checked by the
controller. The explicit 8,192-token context retains the request and
accumulated observations through longer workflows.

The controller logs each exchange as it happens: model proposals, validation
feedback, daemon requests with fixed expectations, daemon results, retries, and
the final pass or failure. Source input is represented by its character and
line counts so large programs do not bury the command exchange. Pass
`--log-full-input` to print complete source bodies. The relevant bounds are:

Before execution, the controller prints an `EXPECTED WORKFLOW` containing the
request's ordered acceptance milestones. It does not predict commands or
outputs; those remain incremental decisions based on actual daemon results.

After a pass, the controller prints the complete successful transcript again,
including each matched command, source input, fixed expectations, and daemon
output. Rejected proposals and failed transactional attempts are omitted from
this final transcript.

```sh
--max-command-attempts 3
--max-step-attempts 4
```

Use `--list-tools` to inspect the MCP schema and the daemon-published command
contract without calling the model.

## Commands and sessions

A transcript starts by creating or opening a tagged session:

```text
repo create <tag> <project>
repo open <tag> <project>
repo use <tag>
repo close <tag>
repo list
```

`create` fails if `<project>/.stratum` exists. `open` fails if it does not
contain a valid Stratum repository. Tags match
`[A-Za-z][A-Za-z0-9_-]{0,63}`, and one daemon connection cannot bind the same
repository to multiple tags.

After selecting a session, transcripts can add or modify a language, add or
modify source, validate source, commit, inspect history, and search stored
artifacts:

```text
language add <name>
language modify <name> <grammar|meta>
language list
language guide <name>
source list
source show <path>
source add <path>
source modify <path>
source copy <from> <to>
source remove <path>
source check <path>
test <path>
run <path>
commit <message>
status
verify
log
branches
artifact search <text>
```

After opening an existing repository, the agent can discover installed
languages and source paths, inspect source content, and search immutable
artifacts before choosing a modifying command. A future blockchain-backed
session should expose this same query surface after linking; the current daemon
does not yet define a blockchain transport or link command.

Only `language modify`, `source add`, and `source modify` consume the separate
step `input`. All command arguments remain on the command line. `test` validates
the source against its declared grammar and Meta structure. `run` loads the
selected language's declared Meta program and invokes its standard source
evaluator through `MetaMachine0`; Lambda currently exports `NormalizeToText`.
Languages that declare syntax but no source evaluator fail explicitly rather
than receiving semantics inferred from grammar alone.

The MCP server exposes no filesystem, shell, generic repository, or
language-specific tools. All session state, command semantics, and repository
effects belong to the persistent Stratum daemon.

## Agent skill and language guidance

The workspace skill at
[`../.github/skills/stratum-agent/SKILL.md`](../.github/skills/stratum-agent/SKILL.md)
teaches compatible agents the generic session, discovery, transactional retry,
and verification workflow. It deliberately contains no language-specific
syntax or semantics.

Do not install a separate editor skill for every language. Stratum should
provide language knowledge as canonical, queryable repository artifacts:

- grammar artifacts define accepted syntax;
- Meta programs define judgments and execution semantics;
- derived toolchains declare available roles such as parsing, validation,
  formatting, and evaluation;
- examples and tests provide usage evidence.

A language may additionally publish agent-oriented guidance as a Stratum
artifact. Such guidance should point to these declarations and explain useful
workflows, but must not duplicate or override them. This keeps local, existing,
and future blockchain-backed repositories self-describing through the same
daemon query surface while the editor skill remains stable.

## What gets installed

The setup utility writes a `stratum` MCP server entry into:

- Claude Code config: `~/.claude.json`
- Claude Desktop config (Linux): `~/.config/Claude/claude_desktop_config.json`

The configured launcher is:

- command: `python3`
- args: `<repo>/tools/stratum_mcp_server.py --repo <repo>`

## Quick setup

From the repository root:

```sh
python3 tools/setup_claude_agent.py --repo "$(pwd)"
```

To configure only one client:

```sh
python3 tools/setup_claude_agent.py --repo "$(pwd)" --clients claude-code
python3 tools/setup_claude_agent.py --repo "$(pwd)" --clients claude-desktop
```

## Doctor check

Validate Claude configuration, launcher startup, MCP handshake and the Stratum tool catalog:

```sh
python3 tools/doctor_claude_agent.py
```

Machine-readable report:

```sh
python3 tools/doctor_claude_agent.py --json
```

## Provided MCP tools

- `stratum_transcript_step`

Its schema accepts `command`, optional `input`, and `expected`. The tool
description is fetched from the daemon when MCP starts, so the daemon remains
the authoritative command contract.

## Notes

- The setup utility preserves other MCP servers and only updates the `stratum` entry.
- Restart Claude Code or Claude Desktop after changing MCP configuration.
- The server is intentionally minimal and stdio-only.
- Session tags live only for one daemon connection; reopening a client requires
  a new `repo open` command.
- Modifying a language definition does not yet regenerate derived language
  artifacts automatically.
