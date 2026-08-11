#!/usr/bin/env python3
"""Configure Stratum MCP entry for Claude Code and Claude Desktop."""

from __future__ import annotations

import argparse
import json
import os
import platform
from pathlib import Path
from typing import Any

SERVER_NAME = "stratum"


def claude_code_config(home: Path) -> Path:
    return home / ".claude.json"


def claude_desktop_config(home: Path) -> Path:
    system = platform.system().lower()
    if system == "darwin":
        return home / "Library" / "Application Support" / "Claude" / "claude_desktop_config.json"
    if system == "windows":
        appdata = os.environ.get("APPDATA")
        if appdata:
            return Path(appdata) / "Claude" / "claude_desktop_config.json"
    return home / ".config" / "Claude" / "claude_desktop_config.json"


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def save_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def update_config(path: Path, server_def: dict[str, Any]) -> str:
    doc = load_json(path)
    mcp_servers = doc.get("mcpServers")
    if not isinstance(mcp_servers, dict):
        mcp_servers = {}
        doc["mcpServers"] = mcp_servers

    before = mcp_servers.get(SERVER_NAME)
    mcp_servers[SERVER_NAME] = server_def
    save_json(path, doc)

    if before is None:
        return "created"
    if before == server_def:
        return "unchanged"
    return "updated"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Configure Claude clients for Stratum MCP")
    parser.add_argument(
        "--repo",
        default=str(Path.cwd()),
        help="Stratum repository root",
    )
    parser.add_argument(
        "--clients",
        default="claude-code,claude-desktop",
        help="Comma-separated clients: claude-code, claude-desktop",
    )
    parser.add_argument(
        "--python",
        default="python3",
        help="Python executable used to launch MCP server",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    repo = Path(args.repo).resolve()
    home = Path.home()

    server = {
        "command": args.python,
        "args": [str(repo / "tools" / "stratum_mcp_server.py"), "--repo", str(repo)],
        "env": {"STRATUM_REPO_ROOT": str(repo)},
    }

    clients = [part.strip() for part in args.clients.split(",") if part.strip()]
    results: list[tuple[str, Path, str]] = []

    for client in clients:
        if client == "claude-code":
            path = claude_code_config(home)
        elif client == "claude-desktop":
            path = claude_desktop_config(home)
        else:
            raise SystemExit(f"Unknown client: {client}")
        status = update_config(path, server)
        results.append((client, path, status))

    for client, path, status in results:
        print(f"{client}: {status} -> {path}")


if __name__ == "__main__":
    main()
