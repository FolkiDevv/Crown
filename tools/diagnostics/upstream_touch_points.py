"""Upstream touch-point check for the simple UI (openspec change simple-ui-redesign, design.md D2).

Read-only: compares the working tree (committed, staged, unstaged and untracked files) with a base ref and lists every
changed file outside the allowed set:
  - the touch-point table of design.md D2 (TOUCH_POINTS below);
  - the fork-only directories app/src/simple/, openspec/, tools/diagnostics/;
  - app/src/test/: only files that do not exist in the base (new JVM unit tests); a modified or deleted upstream test
    is a violation.

Run from the repository root:
    git fetch upstream main
    uv run tools/diagnostics/upstream_touch_points.py [--base upstream/main] [--all]

Exit code 0 when no file is outside the allowed set, 1 when one is, 2 on a git error.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

# design.md D2, the touch-point table (接入点清单). Keep both in sync: a new touch point is added there first.
TOUCH_POINTS: dict[str, str] = {
    "build.gradle.kts": "1: Compose compiler plugin declaration",
    "app/build.gradle.kts": "2: Compose plugin, buildFeatures.compose, dependencies, simple source dirs",
    "app/src/main/AndroidManifest.xml": "3: SimpleActivity declaration",
    "app/src/main/res/menu/main_drawer_menu.xml": "4: 'New interface' drawer item",
    "app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt": "5: SimpleUi.redirect and the drawer handler",
    "app/src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt": "6: SimpleHooks service start/stop",
    "app/src/main/java/io/nekohasekai/sagernet/group/RemoteRouteUpdater.kt": "7: SimpleConfigRepo.refresh",
}

FORK_DIRS = ("app/src/simple/", "openspec/", "tools/diagnostics/")
NEW_ONLY_DIRS = ("app/src/test/",)


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args], cwd=REPO_ROOT, capture_output=True, text=True, check=False
    )
    if result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)}: {result.stderr.strip()}")
    return result.stdout


def changed_files(base: str) -> dict[str, str]:
    """path -> status letter (A/M/D/T) of the working tree against base; untracked files count as added."""
    changes: dict[str, str] = {}
    out = git("diff", "--name-status", "--no-renames", "-z", base, "--")
    parts = out.split("\0")
    for i in range(0, len(parts) - 1, 2):
        status, path = parts[i], parts[i + 1]
        changes[path] = status[:1]
    for path in git("ls-files", "--others", "--exclude-standard", "-z").split("\0"):
        if path:
            changes[path] = "A"
    return changes


def classify(path: str, status: str) -> tuple[bool, str]:
    """(allowed, reason)"""
    if path in TOUCH_POINTS:
        return True, f"touch point {TOUCH_POINTS[path]}"
    for prefix in FORK_DIRS:
        if path.startswith(prefix):
            return True, f"fork directory {prefix}"
    for prefix in NEW_ONLY_DIRS:
        if path.startswith(prefix):
            if status == "A":
                return True, f"new file in {prefix}"
            return False, f"existing upstream file in {prefix} (only new files are allowed)"
    return False, "outside the touch-point list of design.md D2"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="upstream/main", help="upstream ref to compare with (default: upstream/main)")
    parser.add_argument("--all", action="store_true", help="also list allowed changes outside the fork directories")
    args = parser.parse_args()

    try:
        git("rev-parse", "--verify", "--quiet", f"{args.base}^{{commit}}")
        changes = changed_files(args.base)
    except RuntimeError as exc:
        print(f"FATAL: {exc}")
        if args.base.startswith("upstream/"):
            print("hint: git remote add upstream https://github.com/throneproj/ThroneForAndroid && git fetch upstream main")
        return 2

    touched: list[str] = []
    violations: list[str] = []
    for path in sorted(changes):
        status = changes[path]
        allowed, reason = classify(path, status)
        if not allowed:
            violations.append(f"{status} {path}: {reason}")
        elif path in TOUCH_POINTS:
            touched.append(f"{status} {path}: {reason}")
        elif args.all and not path.startswith(FORK_DIRS):
            touched.append(f"{status} {path}: {reason}")

    print(f"base: {args.base}, changed files: {len(changes)}")
    print(f"allowed changes outside the fork directories ({len(touched)}):")
    for line in touched:
        print(f"  {line}")
    print(f"outside the list ({len(violations)}):")
    for line in violations:
        print(f"  {line}")
    return 1 if violations else 0


if __name__ == "__main__":
    sys.exit(main())
