"""Upstream touch-point check for the simple UI (openspec change simple-ui-redesign, design.md D2) and the Crown fork
identity (openspec/specs/repository-governance, requirement "Идентичность форка Crown").

Read-only: compares the working tree (committed, staged, unstaged and untracked files) with the merge base of a base
ref and HEAD (`git merge-base <base> HEAD`, the three-dot view: only the fork's own changes, not commits the base
gained after it) and lists every changed file outside the allowed set:
  - the touch-point table of design.md D2 (TOUCH_POINTS below) and the fork identity points (FORK_IDENTITY_POINTS);
  - the fork-only directories and files (FORK_DIRS): app/src/simple/, openspec/, tools/diagnostics/ and the fork
    identity files;
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
    "app/src/main/AndroidManifest.xml": "3: SimpleActivity and WhitelistZoneReceiver declarations; crown: the crown:// link filter",
    "app/src/main/res/menu/main_drawer_menu.xml": "4: 'New interface' drawer item",
    "app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt": "5: SimpleUi.redirect in onCreate/onNewIntent and the drawer handler; crown: crown:// links opened from outside",
    "app/src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt": "6: SimpleHooks service start/stop",
    "app/src/main/java/io/nekohasekai/sagernet/group/RemoteRouteUpdater.kt": "7: SimpleConfigRepo.refresh",
    "app/src/main/java/moe/matsuri/nb4a/NativeInterface.kt": "8: network listener keyed by the gomobile refnum (leak fix)",
    "app/src/main/java/io/nekohasekai/sagernet/group/SubscriptionFetch.kt": "9: device limit headers of a subscription answer",
    "app/src/main/java/io/nekohasekai/sagernet/group/SubscriptionRefresh.kt": "9: SimpleHooks.onSubscriptionFetched/onSubscriptionRefreshed",
}

# openspec/specs/repository-governance, requirement "Идентичность форка Crown". Keep both in sync.
FORK_IDENTITY_POINTS: dict[str, str] = {
    "buildSrc/src/main/kotlin/Helpers.kt": "crown: package, version and BuildConfig fields from fork.properties, APK name",
    "app/src/main/java/io/nekohasekai/sagernet/update/UpdateChecker.kt": "crown: update repository and changelog file",
    "app/src/main/java/io/nekohasekai/sagernet/ui/AboutFragment.kt": "crown: GitHub link to the update repository",
    "app/src/main/java/io/nekohasekai/sagernet/SagerNet.kt": "crown: displayed version with the Throne base",
    "app/src/main/java/io/nekohasekai/sagernet/ktx/Nets.kt": "crown: User-Agent Crown/Android/ with the Throne version",
    "buildScript/release_assets.py": "crown: update manifest for previews too",
    "buildScript/release_notes.sh": "crown: fork changelog",
    ".github/workflows/preview.yml": "crown: removed, release.yml publishes the previews",
    ".github/workflows/release.yml": "crown: every push to main publishes a preview or stable release (fork.properties RELEASE)",
    ".github/workflows/ci.yml": "crown: pull requests, main and develop only, release plan check",
    "README.md": "crown: the fork's README",
    "app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt": "crown: daily update check on by default",
    "app/src/main/res/xml/settings_general.xml": "crown: daily update check switch on by default",
    "app/src/main/java/io/nekohasekai/sagernet/database/SettingsRegistry.kt": "crown: pre-releases looked for by default on a preview build",
    "app/src/main/java/io/nekohasekai/sagernet/ui/SubscribeFlows.kt": "crown: crown:// links pasted, scanned or shared",
}

FORK_DIRS = (
    "app/src/simple/", "openspec/", "tools/diagnostics/",
    # fork identity files
    "fork.properties", "CROWN_CHANGELOG.md", "CROWN.md", "buildSrc/src/main/kotlin/Fork.kt", "app/src/oss/res/",
    "buildScript/release_plan.sh", ".github/images/crown/", ".coderabbit.yaml", "CLAUDE.md",
)
NEW_ONLY_DIRS = ("app/src/test/",)


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args], cwd=REPO_ROOT, capture_output=True, text=True, check=False
    )
    if result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)}: {result.stderr.strip()}")
    return result.stdout


def changed_files(base: str) -> dict[str, str]:
    """path -> status letter (A/M/D/T) against base, of the working tree and of the index (a staged change stays
    visible when the working copy was restored); untracked files count as added."""
    changes: dict[str, str] = {}
    for cached in (False, True):
        args = ["diff", "--name-status", "--no-renames", "-z"] + (["--cached"] if cached else []) + [base, "--"]
        parts = git(*args).split("\0")
        for i in range(0, len(parts) - 1, 2):
            status, path = parts[i], parts[i + 1]
            # an A in either comparison is only A when the path is missing from base; any other status wins
            if changes.get(path) in (None, "A"):
                changes[path] = status[:1]
    for path in git("ls-files", "--others", "--exclude-standard", "-z").split("\0"):
        if path:
            changes.setdefault(path, "A")  # keep a base-relative status (git rm --cached leaves a D)
    return changes


def classify(path: str, status: str) -> tuple[bool, str]:
    """(allowed, reason)"""
    if path in TOUCH_POINTS:
        return True, f"touch point {TOUCH_POINTS[path]}"
    if path in FORK_IDENTITY_POINTS:
        return True, f"touch point {FORK_IDENTITY_POINTS[path]}"
    for prefix in FORK_DIRS:
        if path.startswith(prefix):
            return True, f"fork directory {prefix}"
    for prefix in NEW_ONLY_DIRS:
        if path.startswith(prefix):
            if status == "A":
                return True, f"new file in {prefix}"
            return False, f"existing upstream file in {prefix} (only new files are allowed)"
    return False, "outside the touch-point lists (design.md D2, repository-governance)"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="upstream/main", help="upstream ref to compare with (default: upstream/main)")
    parser.add_argument("--all", action="store_true", help="also list allowed changes outside the fork directories")
    args = parser.parse_args()

    try:
        git("rev-parse", "--verify", "--quiet", f"{args.base}^{{commit}}")
        # Upstream moves on after the last merge; its own new commits are not the fork's changes.
        merge_base = git("merge-base", args.base, "HEAD").strip()
        changes = changed_files(merge_base)
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
        elif path in TOUCH_POINTS or path in FORK_IDENTITY_POINTS:
            touched.append(f"{status} {path}: {reason}")
        elif args.all and not path.startswith(FORK_DIRS):
            touched.append(f"{status} {path}: {reason}")

    print(f"base: {args.base} (merge base {merge_base[:8]}), changed files: {len(changes)}")
    print(f"allowed changes outside the fork directories ({len(touched)}):")
    for line in touched:
        print(f"  {line}")
    print(f"outside the list ({len(violations)}):")
    for line in violations:
        print(f"  {line}")
    return 1 if violations else 0


if __name__ == "__main__":
    sys.exit(main())
