# Stratum Agent for Claude Clients

This repository now includes a local MCP server and setup utility for Claude clients.

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

- `stratum_foundation_verify`
- `stratum_foundation_reconstruct`
- `stratum_run_staircase`
- `stratum_run_cleanroom`

These tools run Stratum commands in the selected repository root and return stdout/stderr text.

## Notes

- The setup utility preserves other MCP servers and only updates the `stratum` entry.
- Restart Claude Code or Claude Desktop after changing MCP configuration.
- The server is intentionally minimal and stdio-only.
