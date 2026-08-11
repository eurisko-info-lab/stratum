---
name: stratum-agent
description: 'Operate Stratum repositories through the daemon transcript API. Use when creating or opening a Stratum repository, discovering languages or code, modifying language definitions or sources, validating, running declared evaluators, committing, searching artifacts, verifying history, or working with a future blockchain-backed Stratum session.'
argument-hint: 'Describe the repository task and expected result'
user-invocable: true
---

# Stratum Agent

Use Stratum as the sole owner of repository reads, writes, history, and execution. Do not use filesystem or process tools to inspect or mutate files inside a Stratum project.

## Command Loop

1. Fetch the daemon-published command API; do not assume a static command catalog.
2. Start the session with exactly one `repo create <tag> <project>` or `repo open <tag> <project>` command. Use a future daemon-published link command for remote or blockchain-backed repositories; never invent one.
3. Propose only the next command, with optional source input and exact stable expected lines. Use an empty expectation for dynamic output.
4. Execute it with `stratum_transcript_step` and inspect the daemon result.
5. On failure or expectation mismatch, preserve the original expectation and vary only the current command or input. The daemon rolls back failed attempts.
6. After success, use the actual observation to choose the next command.
7. Finish only after the requested state and verification evidence have been observed.

Never produce a complete transcript before execution. Never run future commands before the current command succeeds.

## Discovery

After opening an existing repository, discover its state before changing it:

- `language list` for installed project languages.
- `language guide <name>` for language-owned syntax, semantics, and examples.
- `source list` for available source paths.
- `source show <path>` for exact source content.
- `source copy <from> <to>` when exact repository-provided bytes must be preserved.
- `artifact search <text>` for immutable repository and history artifacts.
- `status`, `branches`, and `log` for working and publication state.

Use the same query surface after a remote or blockchain-backed session is linked.

## Language Knowledge

Do not rely on an editor skill per language. Language truth belongs to Stratum:

- grammar artifacts define accepted syntax;
- Meta programs define semantics and judgments;
- derived toolchain artifacts define available roles such as parse, validate, format, or evaluate;
- repository examples and tests provide task-specific usage evidence.

Query those artifacts through daemon commands. If a language lacks a declared evaluator or another requested role, report that capability as unavailable rather than inventing host behavior.

Before authoring source, query the installed language's guide when one exists.
Validate its statements against the grammar and declared Meta roles rather than
substituting syntax from a familiar language.

A language may publish optional agent guidance as a canonical Stratum artifact, but that guidance supplements and references its grammar, Meta program, toolchain roles, examples, and tests. It must not become a second source of semantic truth.

## Repository Invariants

- All project effects pass through Stratum commands and its journal.
- Session tags are scoped to one daemon connection.
- Expected output remains immutable during retries.
- Dynamic commands use empty expectations.
- Paths from the user request remain exact and repository-relative.
- Commit only when requested and verify after publication when the task requires it.
- Log each model proposal, validation response, daemon request, and daemon result.
