#!/usr/bin/env python3
"""Minimal MCP stdio server exposing Stratum operations to Claude clients."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

PROTOCOL_VERSION = "2024-11-05"
SERVER_NAME = "stratum"
SERVER_VERSION = "0.1.0"


@dataclass
class ToolResult:
    text: str
    is_error: bool = False


class McpServer:
    def __init__(self, repo_root: Path) -> None:
        self.repo_root = repo_root

    def serve(self) -> None:
        while True:
            message = self._read_message()
            if message is None:
                return
            if "method" not in message:
                continue
            method = message["method"]
            params = message.get("params", {})
            msg_id = message.get("id")

            if method == "initialize":
                result = {
                    "protocolVersion": PROTOCOL_VERSION,
                    "capabilities": {"tools": {}},
                    "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
                    "instructions": (
                        "Stratum MCP server. Tools run foundation verification and "
                        "staircase/cleanroom checks in the configured repository."
                    ),
                }
                self._write_response(msg_id, result)
            elif method == "notifications/initialized":
                continue
            elif method == "ping":
                self._write_response(msg_id, {})
            elif method == "tools/list":
                self._write_response(msg_id, {"tools": self._tools_schema()})
            elif method == "tools/call":
                name = params.get("name", "")
                arguments = params.get("arguments", {})
                result = self._call_tool(name, arguments)
                payload = {
                    "content": [{"type": "text", "text": result.text}],
                    "isError": result.is_error,
                }
                self._write_response(msg_id, payload)
            else:
                if msg_id is not None:
                    self._write_error(msg_id, -32601, f"Method not found: {method}")

    def _tools_schema(self) -> list[dict[str, Any]]:
        return [
            {
                "name": "stratum_foundation_verify",
                "description": "Run 'foundation verify' for a foundation directory.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "dir": {
                            "type": "string",
                            "description": "Foundation directory path, e.g. foundations/F11",
                        }
                    },
                    "required": ["dir"],
                    "additionalProperties": False,
                },
            },
            {
                "name": "stratum_foundation_reconstruct",
                "description": "Run 'foundation reconstruct' for a foundation directory.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "dir": {
                            "type": "string",
                            "description": "Foundation directory path, e.g. foundations/F11",
                        }
                    },
                    "required": ["dir"],
                    "additionalProperties": False,
                },
            },
            {
                "name": "stratum_run_staircase",
                "description": "Run the full staircase verification script.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "workers": {
                            "type": "integer",
                            "minimum": 1,
                            "description": "Optional parallel worker count.",
                        }
                    },
                    "additionalProperties": False,
                },
            },
            {
                "name": "stratum_run_cleanroom",
                "description": "Run clean-room reconstruction verification.",
                "inputSchema": {
                    "type": "object",
                    "properties": {},
                    "additionalProperties": False,
                },
            },
        ]

    def _call_tool(self, name: str, arguments: dict[str, Any]) -> ToolResult:
        try:
            if name == "stratum_foundation_verify":
                directory = str(arguments["dir"])
                return self._run_stratum_cli(["foundation", "verify", "--dir", directory])
            if name == "stratum_foundation_reconstruct":
                directory = str(arguments["dir"])
                return self._run_stratum_cli(["foundation", "reconstruct", "--dir", directory])
            if name == "stratum_run_staircase":
                workers = arguments.get("workers")
                cmd = ["./tools/staircase.sh"]
                if workers is not None:
                    cmd.append(str(int(workers)))
                return self._run(cmd, timeout_seconds=1800)
            if name == "stratum_run_cleanroom":
                return self._run(["./tools/cleanroom.sh"], timeout_seconds=1800)
            return ToolResult(f"Unknown tool: {name}", is_error=True)
        except KeyError as exc:
            return ToolResult(f"Missing required argument: {exc}", is_error=True)
        except Exception as exc:  # defensive path for MCP callers
            return ToolResult(f"Tool execution failed: {exc}", is_error=True)

    def _run_stratum_cli(self, args: list[str]) -> ToolResult:
        cmd = [
            "sbt",
            "-batch",
            "--error",
            f"runMain stratum.cli.Stratum {' '.join(args)}",
        ]
        return self._run(cmd, timeout_seconds=1800)

    def _run(self, cmd: list[str], timeout_seconds: int) -> ToolResult:
        completed = subprocess.run(
            cmd,
            cwd=self.repo_root,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout_seconds,
            check=False,
        )
        stdout = completed.stdout.strip()
        stderr = completed.stderr.strip()
        text_parts = [f"$ {' '.join(cmd)}", ""]
        if stdout:
            text_parts.append(stdout)
        if stderr:
            if stdout:
                text_parts.append("")
            text_parts.append("[stderr]")
            text_parts.append(stderr)
        if not stdout and not stderr:
            text_parts.append("(no output)")
        is_error = completed.returncode != 0
        if is_error:
            text_parts.append("")
            text_parts.append(f"exit code: {completed.returncode}")
        return ToolResult("\n".join(text_parts), is_error=is_error)

    def _read_message(self) -> dict[str, Any] | None:
        headers: dict[str, str] = {}
        while True:
            line = sys.stdin.buffer.readline()
            if not line:
                return None
            if line in (b"\r\n", b"\n"):
                break
            try:
                key, value = line.decode("utf-8").split(":", 1)
            except ValueError:
                continue
            headers[key.strip().lower()] = value.strip()
        length_text = headers.get("content-length")
        if length_text is None:
            return None
        length = int(length_text)
        payload = sys.stdin.buffer.read(length)
        if not payload:
            return None
        return json.loads(payload.decode("utf-8"))

    def _write_response(self, msg_id: Any, result: Any) -> None:
        payload = {"jsonrpc": "2.0", "id": msg_id, "result": result}
        self._write_message(payload)

    def _write_error(self, msg_id: Any, code: int, message: str) -> None:
        payload = {
            "jsonrpc": "2.0",
            "id": msg_id,
            "error": {"code": code, "message": message},
        }
        self._write_message(payload)

    def _write_message(self, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        header = f"Content-Length: {len(body)}\r\n\r\n".encode("ascii")
        sys.stdout.buffer.write(header)
        sys.stdout.buffer.write(body)
        sys.stdout.buffer.flush()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run Stratum MCP server over stdio")
    parser.add_argument(
        "--repo",
        default=os.environ.get("STRATUM_REPO_ROOT", os.getcwd()),
        help="Path to stratum repository root",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    repo_root = Path(args.repo).resolve()
    server = McpServer(repo_root)
    server.serve()


if __name__ == "__main__":
    main()
