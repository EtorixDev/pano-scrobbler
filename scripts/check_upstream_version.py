#!/usr/bin/env python3
"""Check release versions against the upstream history integrated into HEAD."""

import argparse
from pathlib import Path
import re
import subprocess
import sys


class VersionCheckError(Exception):
    """An actionable release metadata or Git history error."""


def git(repo: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(repo), *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )


def git_output(repo: Path, *args: str) -> str:
    result = git(repo, *args)
    if result.returncode != 0:
        raise VersionCheckError(result.stderr.strip() or f"Git command failed: {' '.join(args)}")
    return result.stdout.strip()


def parse_version(contents: str, source: str) -> int:
    value = contents.strip()
    if re.fullmatch(r"[1-9][0-9]*", value) is None:
        raise VersionCheckError(f"{source} must contain one positive decimal integer.")
    try:
        number = int(value)
    except ValueError as error:
        raise VersionCheckError(f"{source} must contain one positive decimal integer.") from error
    return number


def read_version(path: Path) -> int:
    try:
        return parse_version(path.read_text(encoding="utf-8"), path.name)
    except (OSError, UnicodeError) as error:
        raise VersionCheckError(f"Cannot read {path.name}: {error}") from error


def check(repo: Path, upstream_ref: str) -> str:
    root = Path(git_output(repo, "rev-parse", "--show-toplevel"))
    if git_output(root, "rev-parse", "--is-shallow-repository") == "true":
        raise VersionCheckError("Git history is shallow. Provide full upstream history before checking release versions.")

    resolved = git(root, "rev-parse", "--verify", "--quiet", "--end-of-options", f"{upstream_ref}^{{commit}}")
    if resolved.returncode != 0:
        raise VersionCheckError(f"Upstream ref '{upstream_ref}' is missing or does not name a commit.")
    upstream_commit = resolved.stdout.strip()

    bases = git(root, "merge-base", "--all", "HEAD", upstream_commit)
    if bases.returncode != 0:
        raise VersionCheckError(f"HEAD and upstream ref '{upstream_ref}' have no common history.")
    base_commits = bases.stdout.splitlines()
    if len(base_commits) != 1:
        raise VersionCheckError(f"HEAD and upstream ref '{upstream_ref}' have multiple merge bases. Resolve the ambiguous upstream history.")
    integrated_commit = base_commits[0]

    upstream_file = git(root, "cat-file", "blob", f"{integrated_commit}:version.txt")
    if upstream_file.returncode != 0:
        raise VersionCheckError(f"Integrated upstream commit {integrated_commit[:12]} has no readable version.txt.")
    integrated_version = parse_version(
        upstream_file.stdout, f"Integrated upstream version.txt at {integrated_commit[:12]}"
    )

    local_version = read_version(root / "version.txt")
    read_version(root / "version-fork.txt")  # The fork version is valid but independent.
    if local_version != integrated_version:
        raise VersionCheckError(
            f"version.txt is {local_version}, but integrated upstream commit "
            f"{integrated_commit[:12]} has version.txt {integrated_version}."
        )

    return (
        f"version.txt {local_version} matches integrated upstream commit "
        f"{integrated_commit[:12]} from {upstream_ref}."
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream-ref", required=True, help="Locally available, full-history upstream ref.")
    parser.add_argument("--repo", type=Path, default=Path.cwd(), help="Repository path (default: current directory).")
    args = parser.parse_args()

    try:
        print(check(args.repo, args.upstream_ref))
    except VersionCheckError as error:
        print(f"Release version check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
