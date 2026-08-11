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
SERVER_VERSION = "0.2.0"


@dataclass
class ToolResult:
    text: str
    is_error: bool = False


class RepoDaemon:
    def __init__(self, repo_root: Path) -> None:
        self.repo_root = repo_root
        self.process: subprocess.Popen[str] | None = None

    def transcript_api(self) -> str:
        response = self._request({"method": "transcript-api"})
        if int(response.get("code", 1)) != 0:
            raise RuntimeError("Stratum daemon rejected transcript API discovery")
        return str(response.get("description", ""))

    def transcript_step(
        self,
        command: str,
        input_text: str | None,
        expected: list[str],
    ) -> ToolResult:
        payload: dict[str, Any] = {
            "method": "transcript-step",
            "command": command,
            "expected": expected,
        }
        if input_text is not None:
            payload["input"] = input_text
        return self._result(self._request(payload))

    def _request(self, payload: dict[str, Any]) -> dict[str, Any]:
        if self.process is None or self.process.poll() is not None:
            self._start()
        assert self.process is not None and self.process.stdin is not None and self.process.stdout is not None
        self.process.stdin.write(json.dumps(payload, separators=(",", ":")) + "\n")
        self.process.stdin.flush()
        line = self.process.stdout.readline()
        if not line:
            self.close()
            raise RuntimeError("Stratum repository daemon stopped unexpectedly")
        return json.loads(line)

    @staticmethod
    def _result(response: dict[str, Any]) -> ToolResult:
        code = int(response.get("code", 1))
        lines = response.get("lines", [])
        text = "\n".join(str(item) for item in lines) or "(no output)"
        return ToolResult(text, is_error=code != 0)

    def close(self) -> None:
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.process.kill()
        self.process = None

    def _start(self) -> None:
        self.process = subprocess.Popen(
            ["bash", str(self.repo_root / "tools" / "stratum_repo_daemon.sh")],
            cwd=self.repo_root,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
        )


class McpServer:
    def __init__(self, repo_root: Path) -> None:
        self.repo_root = repo_root
        self.repo_daemon = RepoDaemon(repo_root)
        self.message_framing: str | None = None
        self.transcript_api = self.repo_daemon.transcript_api()

    def serve(self) -> None:
        try:
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
                            "Execute an ordered adaptive transcript one command at a time. Start by "
                            "creating or opening a tagged repository session. Failed commands or "
                            "expectation mismatches are rolled back by Stratum."
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
        finally:
            self.repo_daemon.close()

    def _tools_schema(self) -> list[dict[str, Any]]:
        return [
            {
                "name": "stratum_transcript_step",
                "description": self.transcript_api,
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "command": {"type": "string", "description": "One transcript command to execute"},
                        "input": {"type": "string", "description": "Optional source payload for add or modify commands"},
                        "expected": {
                            "type": "array",
                            "items": {"type": "string"},
                            "description": "Output lines that must occur in order for this step to commit",
                        },
                    },
                    "required": ["command", "expected"],
                    "additionalProperties": False,
                },
            }
        ]

    def _call_tool(self, name: str, arguments: dict[str, Any]) -> ToolResult:
        try:
            if name == "stratum_transcript_step":
                command = str(arguments["command"])
                input_text = str(arguments["input"]) if "input" in arguments else None
                expected = arguments["expected"]
                if not isinstance(expected, list) or not all(isinstance(line, str) for line in expected):
                    return ToolResult("expected must be an array of strings", is_error=True)
                return self.repo_daemon.transcript_step(command, input_text, expected)
            return ToolResult(f"Unknown tool: {name}", is_error=True)
        except KeyError as exc:
            return ToolResult(f"Missing required argument: {exc}", is_error=True)
        except Exception as exc:  # defensive path for MCP callers
            return ToolResult(f"Tool execution failed: {exc}", is_error=True)

    def _read_message(self) -> dict[str, Any] | None:
        first_line = sys.stdin.buffer.readline()
        while first_line in (b"\r\n", b"\n"):
            first_line = sys.stdin.buffer.readline()
        if not first_line:
            return None

        if first_line.lstrip().startswith(b"{"):
            self.message_framing = "newline"
            return json.loads(first_line.decode("utf-8"))

        self.message_framing = "content-length"
        headers: dict[str, str] = {}
        line = first_line
        while True:
            if line in (b"\r\n", b"\n"):
                break
            try:
                key, value = line.decode("utf-8").split(":", 1)
            except ValueError:
                pass
            else:
                headers[key.strip().lower()] = value.strip()
            line = sys.stdin.buffer.readline()
            if not line:
                return None
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
        if self.message_framing == "newline":
            sys.stdout.buffer.write(body + b"\n")
        else:
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
