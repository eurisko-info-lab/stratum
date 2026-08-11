#!/usr/bin/env python3
"""Validate Claude MCP configuration and Stratum MCP handshake."""

from __future__ import annotations

import argparse
import json
import os
import platform
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any

SERVER_NAME = "stratum"


@dataclass
class ClientReport:
    client: str
    path: str
    config_status: str
    launcher_status: str
    handshake_status: str
    tools_status: str
    details: str


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


def send_jsonrpc(proc: subprocess.Popen[str], payload: dict[str, Any]) -> None:
    body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    header = f"Content-Length: {len(body)}\r\n\r\n".encode("ascii")
    assert proc.stdin is not None
    proc.stdin.write(header.decode("ascii") + body.decode("utf-8"))
    proc.stdin.flush()


def read_jsonrpc(proc: subprocess.Popen[str], timeout: float = 8.0) -> dict[str, Any]:
    assert proc.stdout is not None
    headers: dict[str, str] = {}
    while True:
        line = proc.stdout.readline()
        if line == "":
            raise RuntimeError("MCP server closed stream")
        if line in ("\r\n", "\n"):
            break
        if ":" in line:
            key, value = line.split(":", 1)
            headers[key.strip().lower()] = value.strip()
    length = int(headers.get("content-length", "0"))
    if length <= 0:
        raise RuntimeError("Missing content-length in MCP response")
    body = proc.stdout.read(length)
    if not body:
        raise RuntimeError("Empty MCP response body")
    return json.loads(body)


def check_client(client: str, path: Path) -> ClientReport:
    doc = load_json(path)
    mcp_servers = doc.get("mcpServers")
    if not isinstance(mcp_servers, dict):
        return ClientReport(client, str(path), "missing", "missing", "not-run", "not-run", "mcpServers missing")

    server = mcp_servers.get(SERVER_NAME)
    if not isinstance(server, dict):
        return ClientReport(client, str(path), "missing", "missing", "not-run", "not-run", "stratum entry missing")

    command = server.get("command")
    args = server.get("args")
    env = server.get("env") or {}

    if not isinstance(command, str) or not isinstance(args, list) or not all(isinstance(a, str) for a in args):
        return ClientReport(client, str(path), "invalid", "invalid", "not-run", "not-run", "invalid command/args")

    launch_env = os.environ.copy()
    if isinstance(env, dict):
        for key, value in env.items():
            if isinstance(key, str) and isinstance(value, str):
                launch_env[key] = value

    try:
        proc = subprocess.Popen(
            [command, *args],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=launch_env,
        )
    except Exception as exc:
        return ClientReport(client, str(path), "ok", "failed", "not-run", "not-run", f"launch failed: {exc}")

    try:
        send_jsonrpc(proc, {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}})
        init = read_jsonrpc(proc)
        if "error" in init:
            return ClientReport(client, str(path), "ok", "ok", "failed", "not-run", f"initialize error: {init['error']}")

        send_jsonrpc(proc, {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})
        send_jsonrpc(proc, {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
        listed = read_jsonrpc(proc)
        if "error" in listed:
            return ClientReport(client, str(path), "ok", "ok", "ok", "failed", f"tools/list error: {listed['error']}")

        tools = listed.get("result", {}).get("tools", [])
        names = {tool.get("name") for tool in tools if isinstance(tool, dict)}
        expected = {
            "stratum_foundation_verify",
            "stratum_foundation_reconstruct",
            "stratum_run_staircase",
            "stratum_run_cleanroom",
        }
        if not expected.issubset(names):
            missing = sorted(expected - names)
            return ClientReport(client, str(path), "ok", "ok", "ok", "failed", f"missing tools: {', '.join(missing)}")

        return ClientReport(client, str(path), "ok", "ok", "ok", "ok", "handshake and catalog verified")
    except Exception as exc:
        return ClientReport(client, str(path), "ok", "ok", "failed", "failed", str(exc))
    finally:
        try:
            proc.terminate()
            proc.wait(timeout=2)
        except Exception:
            proc.kill()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Doctor for Stratum Claude MCP integration")
    parser.add_argument(
        "--clients",
        default="claude-code,claude-desktop",
        help="Comma-separated clients: claude-code, claude-desktop",
    )
    parser.add_argument("--json", action="store_true", help="Emit JSON report")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    home = Path.home()

    target_paths: dict[str, Path] = {
        "claude-code": claude_code_config(home),
        "claude-desktop": claude_desktop_config(home),
    }

    reports: list[ClientReport] = []
    for client in [part.strip() for part in args.clients.split(",") if part.strip()]:
        if client not in target_paths:
            raise SystemExit(f"Unknown client: {client}")
        reports.append(check_client(client, target_paths[client]))

    overall_ok = all(
        report.config_status == "ok"
        and report.launcher_status == "ok"
        and report.handshake_status == "ok"
        and report.tools_status == "ok"
        for report in reports
    )

    if args.json:
        payload = {
            "integration": {
                "server": SERVER_NAME,
                "status": "ok" if overall_ok else "failed",
                "checkedClients": [report.__dict__ for report in reports],
            }
        }
        print(json.dumps(payload, indent=2))
    else:
        print(f"stratum doctor: {'ok' if overall_ok else 'failed'}")
        for report in reports:
            print(
                f"- {report.client}: config={report.config_status} launch={report.launcher_status} "
                f"handshake={report.handshake_status} tools={report.tools_status} ({report.path})"
            )
            print(f"  {report.details}")

    raise SystemExit(0 if overall_ok else 1)


if __name__ == "__main__":
    main()
