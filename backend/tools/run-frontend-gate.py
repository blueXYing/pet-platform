#!/usr/bin/env python3
"""Run build checks only. No mock or Linux build can certify WeChat runtime."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess

CAPABILITIES = {
    "web-build": ("frontend-admin", ("typecheck", "build", "check:boundaries")),
    "miniapp-build": ("frontend-miniapp", ("typecheck", "test", "build:weapp", "check:package")),
}


def plan(root, capability):
    if capability not in CAPABILITIES:
        raise ValueError(f"NOT_EXECUTED: {capability} has no verified runner/test entrypoint yet; QA-001 or later Issue must implement it")
    folder, scripts = CAPABILITIES[capability]
    project = root / folder
    if not (project / "package.json").is_file():
        raise ValueError(f"NOT_IMPLEMENTED: {folder}/package.json missing")
    package = json.loads((project / "package.json").read_text(encoding="utf-8-sig"))
    if not (project / "package-lock.json").is_file():
        raise ValueError(f"NOT_EXECUTED: {folder} needs its owner's committed npm lockfile")
    missing = [s for s in scripts if not package.get("scripts", {}).get(s)]
    if missing:
        raise ValueError(f"NOT_EXECUTED: {folder} missing required scripts: {missing}")
    if capability == "miniapp-build" and "weapp" not in package["scripts"]["build:weapp"]:
        raise ValueError("NOT_EXECUTED: miniapp command must explicitly target weapp")
    return project, [["npm", "ci"], *[["npm", "run", s] for s in scripts]]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("capability")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    try:
        project, commands = plan(args.root, args.capability)
        npm = shutil.which("npm")
        if not npm:
            raise ValueError("NOT_EXECUTED: npm unavailable")
        for command in commands:
            subprocess.run([npm, *command[1:]], cwd=project, check=True)
        message = f"PASS: {args.capability} configured commands only. Node fixture/package checks are not platform acceptance. Lint not configured by C-001/A-001. Runtime, real API, permission/payment and VIS acceptance: NOT_EXECUTED."
        print(message)
        if os.getenv("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
                summary.write(message + "\n")
        return 0
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(error)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
