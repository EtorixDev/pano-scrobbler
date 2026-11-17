#!/usr/bin/env python3
"""Validate the native build selected for all release platforms."""

import argparse
import json
from pathlib import Path
import re


REQUIRED_ARTIFACTS = (
    "libs-linux-x64",
    "libs-linux-arm64",
    "libs-windows-x64",
)


def verify(run: dict, artifacts: dict, run_id: str, commit: str) -> None:
    if re.fullmatch(r"[1-9][0-9]*", run_id) is None:
        raise ValueError("Native run ID must be a positive integer.")
    if re.fullmatch(r"[0-9a-f]{40}", commit) is None:
        raise ValueError("Native commit must be a full lowercase Git SHA.")
    expected_run_id = int(run_id)
    if run.get("id") != expected_run_id or run.get("head_sha") != commit:
        raise ValueError("Native run does not match the approved run ID and commit.")
    if run.get("path") != ".github/workflows/build.yml" or run.get("head_branch") != "main":
        raise ValueError("Native run must use the build workflow on main.")
    if run.get("event") not in {"push", "workflow_dispatch"}:
        raise ValueError("Native run must be a main push or manual build.")
    if run.get("status") != "completed" or run.get("conclusion") != "success":
        raise ValueError("Native build has not completed successfully.")

    for name in REQUIRED_ARTIFACTS:
        matches = [artifact for artifact in artifacts.get("artifacts", []) if artifact.get("name") == name]
        if len(matches) != 1:
            raise ValueError(f"Expected exactly one {name} artifact.")
        artifact = matches[0]
        if artifact.get("expired") is not False or artifact.get("size_in_bytes", 0) <= 0:
            raise ValueError(f"Native artifact {name} is expired or empty.")
        provenance = artifact.get("workflow_run", {})
        if provenance.get("id") != expected_run_id or provenance.get("head_sha") != commit:
            raise ValueError(f"Native artifact {name} does not belong to the approved build.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-json", required=True, type=Path)
    parser.add_argument("--artifacts-json", required=True, type=Path)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    try:
        verify(
            json.loads(args.run_json.read_text()),
            json.loads(args.artifacts_json.read_text()),
            args.run_id,
            args.commit,
        )
    except (ValueError, OSError) as error:
        parser.error(str(error))
    print(f"run_id={args.run_id}")
    print(f"commit={args.commit}")


if __name__ == "__main__":
    main()
