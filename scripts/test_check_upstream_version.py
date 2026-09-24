"""Regression tests for the release version history check."""

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("check_upstream_version.py")


class UpstreamVersionCheckTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture_root = Path(tempfile.mkdtemp(prefix="pano-version-check-"))
        self.repo = self.fixture_root / "repo"
        self.repo.mkdir()
        self.env = os.environ.copy()
        self.env.update(
            GIT_CONFIG_NOSYSTEM="1",
            GIT_CONFIG_GLOBAL=os.devnull,
            GIT_AUTHOR_NAME="Release Test",
            GIT_AUTHOR_EMAIL="release-test@example.invalid",
            GIT_COMMITTER_NAME="Release Test",
            GIT_COMMITTER_EMAIL="release-test@example.invalid",
        )

        self.git("init", "-q", "-b", "main")
        self.write_version("442")
        (self.repo / "version-fork.txt").write_text("11\n", encoding="utf-8")
        self.git("add", "version.txt", "version-fork.txt")
        self.git("commit", "-qm", "Base version")

        self.git("switch", "-q", "-c", "upstream-main")
        self.write_version("445")
        self.git("add", "version.txt")
        self.git("commit", "-qm", "Upstream release 445")

        self.git("switch", "-q", "main")
        self.git("merge", "-q", "--no-ff", "upstream-main", "-m", "Integrate upstream release")

    def git(self, *args: str) -> str:
        result = subprocess.run(
            ["git", "-C", str(self.repo), *args],
            capture_output=True,
            text=True,
            env=self.env,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.strip()

    def write_version(self, value: str) -> None:
        (self.repo / "version.txt").write_text(f"{value}\n", encoding="utf-8")

    def guard(self, ref: str = "upstream-main", repo: Path | None = None) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--repo", str(repo or self.repo), "--upstream-ref", ref],
            capture_output=True,
            text=True,
            env=self.env,
            check=False,
        )

    def test_integrated_upstream_version_passes_with_independent_fork_version(self) -> None:
        (self.repo / "version-fork.txt").write_text("999\n", encoding="utf-8")
        result = self.guard()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("version.txt 445 matches integrated upstream", result.stdout)

    def test_stale_version_fails_after_upstream_integration(self) -> None:
        self.write_version("442")
        result = self.guard()
        self.assertEqual(result.returncode, 1)
        self.assertIn("version.txt is 442", result.stderr)
        self.assertIn("version.txt 445", result.stderr)

    def test_unmerged_upstream_release_does_not_force_early_bump(self) -> None:
        self.git("switch", "-q", "upstream-main")
        self.write_version("446")
        self.git("add", "version.txt")
        self.git("commit", "-qm", "Upstream release 446")
        self.git("switch", "-q", "main")

        result = self.guard()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("version.txt 445 matches integrated upstream", result.stdout)

    def test_malformed_local_versions_fail(self) -> None:
        for filename, contents in (("version.txt", "44x"), ("version.txt", "0445"), ("version-fork.txt", "eleven")):
            with self.subTest(filename=filename):
                path = self.repo / filename
                original = path.read_text(encoding="utf-8")
                path.write_text(contents, encoding="utf-8")
                result = self.guard()
                self.assertEqual(result.returncode, 1)
                self.assertIn(f"{filename} must contain one positive decimal integer", result.stderr)
                path.write_text(original, encoding="utf-8")

    def test_malformed_integrated_upstream_version_fails(self) -> None:
        base = self.git("rev-parse", "HEAD^1")
        self.git("switch", "-q", "-c", "bad-upstream", base)
        self.write_version("invalid")
        self.git("add", "version.txt")
        self.git("commit", "-qm", "Malformed upstream version")
        self.git("switch", "-q", "main")
        self.git("merge", "-q", "--no-ff", "-s", "ours", "bad-upstream", "-m", "Record upstream history")

        result = self.guard("bad-upstream")
        self.assertEqual(result.returncode, 1)
        self.assertIn("Integrated upstream version.txt", result.stderr)
        self.assertIn("positive decimal integer", result.stderr)

    def test_missing_upstream_ref_fails_clearly(self) -> None:
        result = self.guard("upstream/missing")
        self.assertEqual(result.returncode, 1)
        self.assertIn("Upstream ref 'upstream/missing' is missing", result.stderr)

    def test_shallow_checkout_fails_clearly(self) -> None:
        shallow_repo = self.fixture_root / "shallow"
        clone = subprocess.run(
            ["git", "clone", "--quiet", "--depth=1", "--no-local", str(self.repo), str(shallow_repo)],
            capture_output=True,
            text=True,
            env=self.env,
            check=False,
        )
        self.assertEqual(clone.returncode, 0, clone.stderr)

        result = self.guard("main", shallow_repo)
        self.assertEqual(result.returncode, 1)
        self.assertIn("Git history is shallow", result.stderr)


if __name__ == "__main__":
    unittest.main()
