from __future__ import annotations

import re
import tomllib
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]

REQUIRED_FILES = [
    "README.md",
    "STRATUM.md",
    "stratum.lock",
    "docs/architecture.md",
    "docs/bootstrap-contract.md",
    "docs/genesis.md",
    "docs/staircase.md",
    "genesis/application.canon",
    "genesis/foundation.canon",
    "genesis/expected-verdicts.canon",
    "successor/application2.canon",
    "successor/foundation2.canon",
]

REQUIRED_DIRECTORIES = [
    "bootstrap/cairn-protocol",
    "bootstrap/compatibility",
    "languages/meta0",
    "languages/meta1",
    "languages/grammar",
    "languages/descriptions",
    "languages/foundation",
    "programs/ckc",
    "programs/free-change",
    "programs/apply-change",
    "programs/acceptance",
    "programs/repository",
    "programs/federation",
    "programs/retention",
    "genesis/source",
    "genesis/closure",
    "genesis/adversarial",
    "successor/delta1",
    "verifier-tests/scala",
    "verifier-tests/rust",
    "verifier-tests/lean",
    "tools/package-genesis",
    "tools/inspect-foundation",
    "tools/mutate-fixture",
]

LOCKFILE_KEYS = [
    "cairnSourceCommit",
    "cairnBootstrapVersion",
    "scalaHostDigest",
    "rustHostDigest",
    "genesisSeedDigest",
    "canonTestVectorDigest",
    "metaMachineTestVectorDigest",
]

FORBIDDEN_IMPORT_PATTERNS = [
    re.compile(r"^\s*import\s+cairn\.(?:internal|runtime|legacy)\b"),
    re.compile(r"^\s*from\s+cairn\.(?:internal|runtime|legacy)\b"),
]

SOURCE_SUFFIXES = {
    ".py",
    ".scala",
    ".rs",
    ".java",
    ".kt",
    ".js",
    ".ts",
    ".tsx",
}


class RepositoryContractTest(unittest.TestCase):
    def test_required_files_exist(self) -> None:
        missing = [path for path in REQUIRED_FILES if not (REPO_ROOT / path).is_file()]
        self.assertEqual([], missing, f"missing required files: {missing}")

    def test_required_directories_exist(self) -> None:
        missing = [path for path in REQUIRED_DIRECTORIES if not (REPO_ROOT / path).is_dir()]
        self.assertEqual([], missing, f"missing required directories: {missing}")

    def test_readme_documents_boundary(self) -> None:
        readme = (REPO_ROOT / "README.md").read_text(encoding="utf-8")
        self.assertIn("sibling repository", readme)
        self.assertIn("Cairn Bootstrap Protocol v1", readme)
        self.assertIn("stratum.lock", readme)

    def test_lockfile_contains_required_contract(self) -> None:
        lock = tomllib.loads((REPO_ROOT / "stratum.lock").read_text(encoding="utf-8"))
        for key in LOCKFILE_KEYS:
            self.assertIn(key, lock)
            self.assertIsInstance(lock[key], str)
            self.assertTrue(lock[key].strip(), f"{key} must not be empty")
        self.assertEqual("immutable-git-commit", lock["dependencyMode"])
        self.assertRegex(lock["cairnSourceCommit"], r"^[0-9a-f]{40}$")

    def test_source_files_do_not_import_forbidden_cairn_namespaces(self) -> None:
        offenders: list[str] = []
        for path in REPO_ROOT.rglob("*"):
            if not path.is_file() or path.suffix not in SOURCE_SUFFIXES:
                continue
            if path.name.startswith("test_"):
                continue
            for line in path.read_text(encoding="utf-8").splitlines():
                if any(pattern.search(line) for pattern in FORBIDDEN_IMPORT_PATTERNS):
                    offenders.append(str(path.relative_to(REPO_ROOT)))
                    break
        self.assertEqual([], offenders, f"forbidden Cairn imports found: {offenders}")


if __name__ == "__main__":
    unittest.main()
