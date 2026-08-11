#!/usr/bin/env python3
"""Run the Stratum MCP tools from a local Ollama model."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


DEFAULT_MODEL = "qwen2.5-coder:7b-instruct-q5_K_M"
DEFAULT_OLLAMA_URL = "http://127.0.0.1:11434"
SYSTEM_PROMPT = """You are the Stratum command controller. Work incrementally:
choose only the next command, execute it through the daemon, observe its result,
and then choose the following command. Never generate or return a complete
transcript or future command list. A command has optional source input and fixed
expected output lines. Use only commands published by the Stratum daemon.
Expected lines must be stable complete output lines, not guessed digests,
partial strings, or invented prose. Use an empty expected array when only
successful exit matters. Every command argument belongs in the command string.
The separate input field is only for source text consumed by language modify or
source add/modify.

When a command fails or misses an expected line, revise only that current
command or its input; never change its expectations, skip it, or advance. A
failed attempt is rolled back by the daemon. If no valid variation remains,
return a concise failure reason. All repository effects must come from daemon
commands. Report completion only after the successful observations satisfy the
entire user request. After opening an existing repository or linking a remote
repository, use language list, source list, source show, and artifact search as
needed to discover its available languages and code before proposing changes.
Before authoring source for an installed language, query `language guide <name>`
when it is available. Treat that repository-provided guidance and the declared
grammar as authoritative; never substitute syntax from a familiar language.
"""


class McpClient:
    def __init__(self, repo_root: Path) -> None:
        self.repo_root = repo_root
        self.process: subprocess.Popen[bytes] | None = None
        self.next_id = 1

    def __enter__(self) -> "McpClient":
        self.process = subprocess.Popen(
            [sys.executable, str(self.repo_root / "tools" / "stratum_mcp_server.py"), "--repo", str(self.repo_root)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
        self.request("initialize", {})
        self.notify("notifications/initialized", {})
        return self

    def __exit__(self, *_: object) -> None:
        if self.process is not None:
            self.process.terminate()
            try:
                self.process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.process.kill()

    def request(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        request_id = self.next_id
        self.next_id += 1
        self._write({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
        response = self._read()
        if response.get("id") != request_id:
            raise RuntimeError(f"unexpected MCP response id: {response.get('id')}")
        if "error" in response:
            raise RuntimeError(f"MCP error: {response['error']}")
        return response["result"]

    def notify(self, method: str, params: dict[str, Any]) -> None:
        self._write({"jsonrpc": "2.0", "method": method, "params": params})

    def tools(self) -> list[dict[str, Any]]:
        return self.request("tools/list", {}).get("tools", [])

    def call_tool(self, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        return self.request("tools/call", {"name": name, "arguments": arguments})

    def _write(self, payload: dict[str, Any]) -> None:
        if self.process is None or self.process.stdin is None:
            raise RuntimeError("MCP server is not running")
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.process.stdin.write(f"Content-Length: {len(body)}\r\n\r\n".encode("ascii") + body)
        self.process.stdin.flush()

    def _read(self) -> dict[str, Any]:
        if self.process is None or self.process.stdout is None:
            raise RuntimeError("MCP server is not running")
        headers: dict[str, str] = {}
        while True:
            line = self.process.stdout.readline()
            if not line:
                raise RuntimeError("MCP server closed its output")
            if line in (b"\r\n", b"\n"):
                break
            key, value = line.decode("utf-8").split(":", 1)
            headers[key.lower().strip()] = value.strip()
        length = int(headers["content-length"])
        return json.loads(self.process.stdout.read(length).decode("utf-8"))


def ollama_chat(url: str, payload: dict[str, Any]) -> dict[str, Any]:
    request = urllib.request.Request(
        f"{url.rstrip('/')}/api/chat",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.URLError as exc:
        raise RuntimeError(f"cannot reach Ollama at {url}: {exc.reason}") from exc


def as_ollama_tools(tools: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "type": "function",
            "function": {
                "name": tool["name"],
                "description": tool.get("description", ""),
                "parameters": tool["inputSchema"],
            },
        }
        for tool in tools
    ]


def result_text(result: dict[str, Any]) -> str:
    return "\n".join(
        item.get("text", "") for item in result.get("content", []) if item.get("type") == "text"
    )


def exchange_value(value: dict[str, Any], full_input: bool) -> dict[str, Any]:
    rendered = dict(value)
    input_text = rendered.get("input")
    if isinstance(input_text, str) and not full_input:
        rendered["input"] = {
            "characters": len(input_text),
            "lines": len(input_text.splitlines()),
        }
    return rendered


def log_exchange(direction: str, value: dict[str, Any], full_input: bool = False) -> None:
    print(
        f"[{direction}] {json.dumps(exchange_value(value, full_input), ensure_ascii=False)}",
        flush=True,
    )


def embedded_tool_calls(content: str, tool_names: set[str]) -> list[dict[str, Any]]:
    decoder = json.JSONDecoder()
    calls: list[dict[str, Any]] = []
    position = 0
    while position < len(content):
        start = content.find("{", position)
        if start < 0:
            break
        try:
            value, end = decoder.raw_decode(content, start)
        except json.JSONDecodeError:
            position = start + 1
            continue
        if (
            isinstance(value, dict)
            and value.get("name") in tool_names
            and isinstance(value.get("arguments"), dict)
        ):
            calls.append({"function": {"name": value["name"], "arguments": value["arguments"]}})
        position = end
    return calls


def first_json_object(text: str) -> dict[str, Any]:
    decoder = json.JSONDecoder()
    position = 0
    while position < len(text):
        start = text.find("{", position)
        if start < 0:
            break
        try:
            value, end = decoder.raw_decode(text, start)
        except json.JSONDecodeError:
            position = start + 1
            continue
        if isinstance(value, dict):
            return value
        position = end
    raise RuntimeError(f"model did not return a JSON object: {text}")


def model_json(url: str, model: str, messages: list[dict[str, Any]], keep_alive: int) -> dict[str, Any]:
    response = ollama_chat(
        url,
        {
            "model": model,
            "messages": messages,
            "format": "json",
            "options": {"num_ctx": 8192, "temperature": 0},
            "stream": False,
            "keep_alive": keep_alive,
        },
    )
    content = response.get("message", {}).get("content", "")
    return first_json_object(content)


def validate_step(value: Any, index: int) -> dict[str, Any]:
    if not isinstance(value, dict) or not isinstance(value.get("command"), str):
        raise RuntimeError(f"transcript step {index} has no command")
    if not value["command"].strip() or any(character in value["command"] for character in "\r\n"):
        raise RuntimeError(f"transcript step {index} command must be exactly one nonblank line")
    expected = value.get("expected", [])
    if not isinstance(expected, list) or not all(isinstance(line, str) for line in expected):
        raise RuntimeError(f"transcript step {index} expected must be an array of strings")
    input_text = value.get("input")
    if input_text is not None and not isinstance(input_text, str):
        raise RuntimeError(f"transcript step {index} input must be a string")
    return {"command": value["command"], "expected": expected, **({"input": input_text} if input_text is not None else {})}


def required_expectation(words: list[str]) -> list[str] | None:
    match words:
        case ["repo", action, tag, project] if action in ("create", "open"):
            return [f"session {tag} project {project} repository {project}/.stratum"]
        case ["repo", "use", tag]:
            return [f"using session {tag}"]
        case ["repo", "close", tag]:
            return [f"closed session {tag}"]
        case ["repo", "list"] | ["language", "list"] | ["source", "list"] | ["status"] | ["log"] | ["branches"]:
            return []
        case ["language", "guide", _] | ["source", "show", _] | ["run", _]:
            return []
        case ["language", "add", name]:
            return [f"added language {name}"]
        case ["source", action, path] if action in ("add", "modify"):
            return [f"{'added' if action == 'add' else 'modified'} {path}"]
        case ["source", "copy", source, target]:
            return [f"copied {source} {target}"]
        case ["source", "remove", path]:
            return [f"removed {path}"]
        case ["source", "check", path] | ["test", path]:
            return [f"valid {path}"]
        case ["commit", *_]:
            return ["branch main"]
        case ["verify"]:
            return ["valid branch main"]
        case ["artifact", "search", *_]:
            return []
        case _:
            return None


def recognized_command(words: list[str]) -> bool:
    match words:
        case ["repo", action, _, _] if action in ("create", "open"):
            return True
        case ["repo", action, _] if action in ("use", "close"):
            return True
        case ["repo", "list"] | ["language", "list"]:
            return True
        case ["language", action, _] if action in ("add", "guide"):
            return True
        case ["language", "modify", _, kind] if kind in ("grammar", "meta"):
            return True
        case ["source", action, _] if action in ("show", "add", "modify", "remove", "check"):
            return True
        case ["source", "copy", _, _] | ["source", "list"]:
            return True
        case [action, _] if action in ("test", "run"):
            return True
        case ["commit", _, *_]:
            return True
        case [action] if action in ("status", "verify", "log", "branches"):
            return True
        case ["artifact", "search", _, *_]:
            return True
        case _:
            return False


def church_numeral(observation: str) -> int | None:
    first_line = observation.splitlines()[0] if observation else ""
    prefix = "result \\ v0. \\ v1. "
    if not first_line.startswith(prefix):
        return None
    body = first_line.removeprefix(prefix).strip()
    applications = 0
    while body.startswith("v0 (") and body.endswith(")"):
        applications += 1
        body = body[4:-1].strip()
    if body == "v0 v1":
        return applications + 1
    if body == "v1":
        return applications
    return None


def validate_next_step(value: Any, step_number: int, prompt: str) -> dict[str, Any]:
    if isinstance(value, dict) and isinstance(value.get("done"), str):
        if step_number == 1:
            raise RuntimeError("the task cannot complete before opening a repository session")
        return {"done": value["done"]}
    if isinstance(value, dict) and isinstance(value.get("fail"), str):
        reason = value["fail"]
        normalized = reason.lower()
        completion_shaped = (
            ("satisf" in normalized and ("already" in normalized or "no further action" in normalized))
            or "no action needed" in normalized
            or "no action required" in normalized
            or "no further action" in normalized
            or "task complete" in normalized
        )
        if step_number > 1 and completion_shaped:
            return {"done": reason}
        return {"fail": value["fail"]}

    step = validate_step(value, step_number)
    words = step["command"].split()
    if not recognized_command(words):
        raise RuntimeError(f"unsupported daemon command shape: `{step['command']}`")
    opens_session = words[:2] in (["repo", "create"], ["repo", "open"])
    if step_number == 1 and (len(words) != 4 or not opens_session):
        raise RuntimeError("the first command must be `repo create <tag> <project>` or `repo open <tag> <project>`")
    if step_number > 1 and opens_session:
        raise RuntimeError("the repository session is already open; choose the next repository command")
    if words[:2] == ["repo", "close"] and not re.search(
        r"\b(?:repo close|close (?:the )?(?:repository )?session)\b",
        prompt,
        re.IGNORECASE,
    ):
        raise RuntimeError("the user did not request closing the repository session")

    requested_paths = re.findall(r"(?<![\w.-])[\w.-]+(?:/[\w.-]+)+(?![\w./-])", prompt)
    if step_number == 1 and requested_paths and not any(Path(path) == Path(words[3]) for path in requested_paths):
        raise RuntimeError(
            f"the session project `{words[3]}` must identify one of the requested paths: {', '.join(requested_paths)}"
        )
    if "language" not in prompt.lower() and step["command"].startswith("language "):
        raise RuntimeError("the command must not add or modify a language when the user did not request it")
    requested_sources = re.findall(r"(?<![\w./-])[\w-]+(?:\.[\w-]+)+(?![\w/-])", prompt)
    if words[:2] in (["source", "add"], ["source", "modify"], ["source", "copy"]) and requested_sources:
        target = words[3] if words[:2] == ["source", "copy"] and len(words) == 4 else words[2] if len(words) == 3 else ""
        requested_name = Path(target).name
        if requested_name not in requested_sources or target != requested_name:
            raise RuntimeError(
                f"source mutation path must preserve a literal requested filename: {', '.join(requested_sources)}"
            )
    requested_commit_messages = re.findall(
        r"(?:exact message|message)\s+['\"]([^'\"]+)['\"]",
        prompt,
        re.IGNORECASE,
    )
    if words[:1] == ["commit"] and requested_commit_messages:
        commit_message = step["command"].removeprefix("commit").strip()
        if commit_message not in requested_commit_messages:
            raise RuntimeError(
                "commit must use one of the exact requested messages: "
                + ", ".join(json.dumps(message) for message in requested_commit_messages)
            )
    required = required_expectation(words)
    if required is not None:
        step["expected"] = required
    return step


def next_step(args: argparse.Namespace, command_api: str, step_number: int, completed: list[dict[str, str]]) -> dict[str, Any]:
    requested_paths = re.findall(r"(?<![\w.-])[\w.-]+(?:/[\w.-]+)+(?![\w./-])", args.prompt)
    path_instruction = (
        f"Literal paths from the request (preserve exactly): {json.dumps(requested_paths)}. "
        "Never add placeholder prefixes such as /path/to.\n\n"
        if requested_paths
        else ""
    )
    requested_commit_messages = re.findall(
        r"(?:exact message|message)\s+['\"]([^'\"]+)['\"]",
        args.prompt,
        re.IGNORECASE,
    )
    completed_commands = [item["command"] for item in completed]
    requested_numeral_match = re.search(r"Church numeral\s+(\d+)", args.prompt, re.IGNORECASE)
    if requested_numeral_match:
        requested_numeral = int(requested_numeral_match.group(1))
        observed_numerals = [
            numeral
            for item in completed
            if item["command"].startswith("run ")
            if (numeral := church_numeral(item["observation"])) is not None
        ]
        if observed_numerals:
            observed_numeral = observed_numerals[-1]
            if observed_numeral != requested_numeral:
                return {
                    "fail": (
                        f"evaluation produced Church numeral {observed_numeral}, "
                        f"not requested Church numeral {requested_numeral}"
                    )
                }
            if re.search(r"\bverify\b", args.prompt, re.IGNORECASE) and "verify" not in completed_commands:
                step = validate_next_step({"command": "verify", "expected": []}, step_number, args.prompt)
                log_exchange("controller policy -> daemon", step, args.log_full_input)
                return step
            if re.search(r"\blog\b", args.prompt, re.IGNORECASE) and "log" not in completed_commands:
                step = validate_next_step({"command": "log", "expected": []}, step_number, args.prompt)
                log_exchange("controller policy -> daemon", step, args.log_full_input)
                return step
            return {"done": f"observed the requested Church numeral {requested_numeral}"}
    mandatory_command: str | None = None
    if requested_commit_messages:
        first_commit = f"commit {requested_commit_messages[0]}"
        language_add_index = next(
            (index for index, command in enumerate(completed_commands) if command.startswith("language add ")),
            None,
        )
        language_inspected_after_add = language_add_index is not None and any(
            command == "language list"
            or command == "source list"
            or command.startswith("language guide ")
            or command.startswith("source check languages/")
            or command.startswith("test languages/")
            for command in completed_commands[language_add_index + 1:]
        )
        if first_commit not in completed_commands and language_inspected_after_add:
            mandatory_command = first_commit
        elif len(requested_commit_messages) > 1:
            second_commit = f"commit {requested_commit_messages[1]}"
            requested_sources = re.findall(r"(?<![\w./-])[\w-]+(?:\.[\w-]+)+(?![\w/-])", args.prompt)
            checked_requested_source = any(
                command.startswith("source check ") and Path(command.split(maxsplit=2)[2]).name in requested_sources
                for command in completed_commands
            )
            if second_commit not in completed_commands and checked_requested_source:
                mandatory_command = second_commit
    if mandatory_command:
        step = validate_next_step(
            {"command": mandatory_command, "expected": []},
            step_number,
            args.prompt,
        )
        log_exchange("controller policy -> daemon", step, args.log_full_input)
        return step
    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {
            "role": "user",
            "content": (
                f"Daemon command API:\n{command_api}\n\n"
                f"User request:\n{args.prompt}\n\n"
                f"{path_instruction}"
                f"Successful commands and observations:\n{json.dumps(completed, ensure_ascii=False)}\n\n"
                f"Choose only command {step_number}. Return JSON only as "
                "{\"command\":\"...\",\"input\":\"optional source text\","
                "\"expected\":[\"exact stable line\"]}. If the entire request is already satisfied, "
                "return {\"done\":\"concise summary\"}. If it cannot be completed, return "
                "{\"fail\":\"clear reason\"}. Never return steps or future commands. "
                + (
                    "This is the first command, so create or open the requested repository session. "
                    if step_number == 1
                    else "The repository session is already open; do not create or open it again. "
                )
                + "Never put command arguments in input. "
                "Use expected:[] for dynamic output."
            ),
        },
    ]
    last_error = ""
    for attempt in range(1, args.max_command_attempts + 1):
        proposal: dict[str, Any] | None = None
        try:
            proposal = model_json(args.ollama_url, args.model, messages, args.keep_alive)
            log_exchange("model -> controller", proposal, args.log_full_input)
            validated = validate_next_step(proposal, step_number, args.prompt)
            if mandatory_command and validated.get("command") != mandatory_command:
                raise RuntimeError(f"the next command must be `{mandatory_command}`")
            if completed and validated.get("command") == completed[-1]["command"]:
                raise RuntimeError(
                    f"command `{validated['command']}` just succeeded; choose the next unmet requirement"
                )
            command_words = validated.get("command", "").split()
            requested_commit_messages = re.findall(
                r"(?:exact message|message)\s+['\"]([^'\"]+)['\"]",
                args.prompt,
                re.IGNORECASE,
            )
            if command_words[:2] in (["source", "add"], ["source", "copy"]) and requested_commit_messages:
                first_commit = f"commit {requested_commit_messages[0]}"
                if not any(item["command"] == first_commit for item in completed):
                    raise RuntimeError(
                        f"complete `{first_commit}` before creating the requested program"
                    )
            if command_words[:2] in (["source", "add"], ["source", "copy"]):
                target = command_words[-1]
                extension = Path(target).suffix.removeprefix(".")
                added_languages = {
                    item["command"].split()[2]
                    for item in completed
                    if item["command"].startswith("language add ") and len(item["command"].split()) == 3
                }
                if extension in added_languages and not any(
                    item["command"] == f"language guide {extension}" for item in completed
                ):
                    raise RuntimeError(
                        f"query `language guide {extension}` before authoring {target}"
                    )
            return validated
        except RuntimeError as exc:
            last_error = str(exc)
            log_exchange("controller -> model", {"rejected": last_error})
            if proposal is not None:
                messages.append({"role": "assistant", "content": json.dumps(proposal, separators=(",", ":"))})
            messages.append(
                {
                    "role": "user",
                    "content": (
                        f"Command proposal {attempt} is invalid: {last_error}. Return only the corrected "
                        f"JSON action for command {step_number}, not a transcript or command list."
                    ),
                }
            )
    raise RuntimeError(f"model could not produce command {step_number} after {args.max_command_attempts} attempts: {last_error}")


def repair_step(
    args: argparse.Namespace,
    api: str,
    step_number: int,
    step: dict[str, Any],
    attempts: list[dict[str, Any]],
    completed: list[dict[str, str]],
) -> dict[str, Any]:
    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {
            "role": "user",
            "content": (
                f"Daemon command API:\n{api}\n\n"
                f"Original user request:\n{args.prompt}\n\n"
                f"Successful commands and observations:\n{json.dumps(completed, ensure_ascii=False)}\n\n"
                f"Current transcript step {step_number}:\n{json.dumps(step, ensure_ascii=False)}\n\n"
                f"Failed attempts, including daemon observations:\n{json.dumps(attempts, ensure_ascii=False)}\n\n"
                "Return JSON only. Either propose a variation of this same step as "
                "{\"command\":\"...\",\"input\":\"optional source text\"}, or stop as "
                "{\"fail\":\"clear reason no valid variation remains\"}. Preserve concrete names and paths "
                "from the original request. Do not stop while a command variation can still satisfy the fixed "
                "expected lines. Do not change expected lines."
            ),
        },
    ]
    last_error = ""
    for attempt in range(1, args.max_command_attempts + 1):
        response = model_json(args.ollama_url, args.model, messages, args.keep_alive)
        log_exchange("model -> controller (repair)", response, args.log_full_input)
        if isinstance(response.get("fail"), str):
            return {"fail": response["fail"]}
        variation = {"command": response.get("command"), "expected": step["expected"]}
        if isinstance(response.get("input"), str):
            variation["input"] = response["input"]
        try:
            return validate_next_step(variation, step_number, args.prompt)
        except RuntimeError as exc:
            last_error = str(exc)
            log_exchange("controller -> model", {"rejected_repair": last_error})
            messages.append({"role": "assistant", "content": json.dumps(response, separators=(",", ":"))})
            messages.append(
                {
                    "role": "user",
                    "content": (
                        f"Repair proposal {attempt} is invalid: {last_error}. Return only a corrected variation "
                        "of the current command with the original expected lines unchanged."
                    ),
                }
            )
    return {"fail": f"model could not produce a valid command variation: {last_error}"}


def print_passing_transcript(completed: list[dict[str, Any]]) -> None:
    print("\nPASSING TRANSCRIPT")
    for index, step in enumerate(completed, start=1):
        print(f"\n[{index}] $ {step['command']}")
        if "input" in step:
            print("input:")
            print(step["input"])
        print(f"expected: {json.dumps(step['expected'], ensure_ascii=False)}")
        print("output:")
        print(step["observation"])


def print_expected_workflow(prompt: str) -> None:
    statements = [
        statement.strip()
        for statement in re.split(r"(?<=[.!?])(?:\s+|$)", prompt.strip())
        if statement.strip()
    ]
    execution_policy = (
        "choose and execute only one command at a time",
        "choose one daemon command at a time",
        "after each successful command",
        "do not generate a complete transcript",
    )
    milestones = [
        statement
        for statement in statements
        if not any(policy in statement.lower() for policy in execution_policy)
    ]
    print("EXPECTED WORKFLOW")
    print("Acceptance milestones; commands and outputs are chosen incrementally.")
    for index, milestone in enumerate(milestones, start=1):
        print(f"{index}. {milestone}")


def run_agent(client: McpClient, args: argparse.Namespace) -> int:
    tools = client.tools()
    if len(tools) != 1 or tools[0].get("name") != "stratum_transcript_step":
        raise RuntimeError("Stratum server did not publish the transcript-step API")
    api = tools[0].get("description", "")
    completed: list[dict[str, str]] = []
    passing_steps: list[dict[str, Any]] = []
    print_expected_workflow(args.prompt)

    for step_number in range(1, args.max_steps + 1):
        print(f"\n[step {step_number}] choosing next command...", flush=True)
        planned = next_step(args, api, step_number, completed)
        if "done" in planned:
            print(f"\nTRANSCRIPT PASSED: {len(completed)} steps\n{planned['done']}")
            print_passing_transcript(passing_steps)
            return 0
        if "fail" in planned:
            print(f"\nTRANSCRIPT FAILED before step {step_number}: {planned['fail']}", file=sys.stderr)
            return 1
        current = planned
        attempts: list[dict[str, Any]] = []
        seen: set[str] = set()
        for attempt_number in range(1, args.max_step_attempts + 1):
            identity = json.dumps(
                {"command": current["command"], "input": current.get("input")},
                sort_keys=True,
            )
            if identity in seen:
                attempts.append({"command": current["command"], "observation": "variation repeated an earlier attempt"})
            else:
                seen.add(identity)
                tool_args = {
                    "command": current["command"],
                    "expected": planned["expected"],
                }
                if "input" in current:
                    tool_args["input"] = current["input"]
                print(f"\n[step {step_number} attempt {attempt_number}] $ {current['command']}")
                log_exchange("controller -> daemon", tool_args, args.log_full_input)
                result = client.call_tool("stratum_transcript_step", tool_args)
                observation = result_text(result)
                log_exchange(
                    "daemon -> controller",
                    {"is_error": bool(result.get("isError")), "output": observation},
                )
                print(observation)
                if not result.get("isError"):
                    print(f"[step {step_number} matched]")
                    completed.append({"command": current["command"], "observation": observation})
                    passing_steps.append({**tool_args, "observation": observation})
                    break
                attempts.append(
                    {
                        "command": current["command"],
                        **({"input": current["input"]} if "input" in current else {}),
                        "observation": observation,
                    }
                )

            if attempt_number == args.max_step_attempts:
                print(
                    f"\nTRANSCRIPT FAILED at step {step_number} after {attempt_number} attempts\n"
                    f"command: {planned['command']}\n"
                    f"expected: {json.dumps(planned['expected'])}\n"
                    f"last observation: {attempts[-1]['observation']}",
                    file=sys.stderr,
                )
                return 1
            current = repair_step(args, api, step_number, planned, attempts, completed)
            if "fail" in current:
                print(
                    f"\nTRANSCRIPT FAILED at step {step_number}: {current['fail']}\n"
                    f"command: {planned['command']}\n"
                    f"expected: {json.dumps(planned['expected'])}",
                    file=sys.stderr,
                )
                return 1
        else:
            return 1

    print(f"\nTRANSCRIPT FAILED: exceeded {args.max_steps} successful commands without completion", file=sys.stderr)
    return 1


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run Stratum tools from a local Ollama model")
    parser.add_argument("prompt", nargs="?", help="Task for the local Stratum Agent")
    parser.add_argument("--model", default=DEFAULT_MODEL, help="Ollama model name")
    parser.add_argument("--ollama-url", default=DEFAULT_OLLAMA_URL, help="Ollama server URL")
    parser.add_argument("--keep-alive", type=int, default=-1, help="Seconds to keep the model loaded; -1 keeps it loaded indefinitely")
    parser.add_argument("--repo", default=str(Path(__file__).resolve().parent.parent), help="Stratum checkout")
    parser.add_argument("--max-step-attempts", type=int, default=4, help="Maximum model variations per transcript step")
    parser.add_argument(
        "--max-command-attempts",
        "--max-plan-attempts",
        dest="max_command_attempts",
        type=int,
        default=3,
        help="Maximum attempts to produce each valid next command",
    )
    parser.add_argument("--max-steps", type=int, default=100, help="Maximum successful commands before the task must complete")
    parser.add_argument("--log-full-input", action="store_true", help="Print complete source input in exchange logs")
    parser.add_argument("--list-tools", action="store_true", help="List MCP tools without calling Ollama")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if not args.list_tools and not args.prompt:
        raise SystemExit("provide a prompt or use --list-tools")
    try:
        with McpClient(Path(args.repo).resolve()) as client:
            if args.list_tools:
                for tool in client.tools():
                    print(f"{tool['name']}: {tool.get('description', '')}")
                return
            raise SystemExit(run_agent(client, args))
    except RuntimeError as exc:
        raise SystemExit(f"TRANSCRIPT FAILED: {exc}") from None


if __name__ == "__main__":
    main()