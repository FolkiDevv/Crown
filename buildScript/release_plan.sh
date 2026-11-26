#!/bin/bash
# Crown: the release a merge into main publishes (CROWN.md, "Ветки и выпуск"). fork.properties declares the version
# (VERSION_NAME) and the channel (RELEASE): preview publishes v<VERSION_NAME>-pre.<N>, N being one above the newest
# preview of that version on origin; stable publishes v<VERSION_NAME>. release.yml publishes the plan on every push to
# main; ci.yml checks it on pull requests.
#
#   release_plan.sh           the plan as key=value lines for $GITHUB_OUTPUT: version, channel (stable/preview), build
#                             (999 / N), tag, version_code and exists (the stable tag is already on origin; a preview
#                             always gets a new tag). Fails when the declaration cannot be published.
#   release_plan.sh --check   pull request check: prints what merging into main publishes (also to $GITHUB_STEP_SUMMARY)
#                             and fails when it cannot be published: malformed declaration, a stable release that is
#                             already out, a preview of a released version, a versionCode not above every published one.
#   release_plan.sh --check --warn
#                             the same, but only warns (pull requests into develop: main is still far away).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHECK=false
WARN=false
for arg in "$@"; do
    case "$arg" in
        --check) CHECK=true ;;
        --warn) WARN=true ;;
        *) echo "usage: release_plan.sh [--check [--warn]]" >&2; exit 2 ;;
    esac
done

summary() {
    printf '%s\n' "$*"
    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then printf '%s\n' "$*" >> "$GITHUB_STEP_SUMMARY"; fi
}

fail() {
    if [ "$CHECK" = true ]; then
        summary "Release plan: $*"
        if [ "$WARN" = true ]; then
            echo "::warning::$*"
            exit 0
        fi
    fi
    echo "::error::$*" >&2
    exit 1
}

# versionCode of buildSrc Fork.kt: ((MAJOR·100 + MINOR)·100 + PATCH)·1000 + build
version_code() {
    [[ "$1" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || return 1
    local major=$((10#${BASH_REMATCH[1]})) minor=$((10#${BASH_REMATCH[2]})) patch=$((10#${BASH_REMATCH[3]}))
    if [ "$major" -gt 20 ] || [ "$minor" -gt 99 ] || [ "$patch" -gt 99 ]; then return 1; fi
    echo $((((major * 100 + minor) * 100 + patch) * 1000 + $2))
}

text="$(cat "$ROOT/fork.properties")"
VERSION_NAME="$(printf '%s\n' "$text" | sed -n 's/^VERSION_NAME=//p' | tr -d '\r[:space:]')"
RELEASE="$(printf '%s\n' "$text" | sed -n 's/^RELEASE=//p' | tr -d '\r[:space:]')"
version_code "$VERSION_NAME" 0 > /dev/null ||
    fail "fork.properties: VERSION_NAME must be MAJOR.MINOR.PATCH (MAJOR 0..20, MINOR/PATCH 0..99), got '$VERSION_NAME'"

# The tags already on origin: vX.Y.Z and vX.Y.Z-pre.N
TAGS="$(git -C "$ROOT" ls-remote --tags origin 'refs/tags/v*' | sed -n 's#.*refs/tags/\(v[^^]*\)$#\1#p')" ||
    fail "git ls-remote origin failed"
STABLE_EXISTS=false
if printf '%s\n' "$TAGS" | grep -qxF "v$VERSION_NAME"; then STABLE_EXISTS=true; fi
MAX_CODE=0
MAX_TAG=
LAST_PRE=0
while read -r tag; do
    [ -n "$tag" ] || continue
    if [[ "$tag" =~ ^v([0-9]+\.[0-9]+\.[0-9]+)$ ]]; then
        code="$(version_code "${BASH_REMATCH[1]}" 999)" || continue
    elif [[ "$tag" =~ ^v([0-9]+\.[0-9]+\.[0-9]+)-pre\.([0-9]+)$ ]]; then
        code="$(version_code "${BASH_REMATCH[1]}" "$((10#${BASH_REMATCH[2]}))")" || continue
        if [ "${BASH_REMATCH[1]}" = "$VERSION_NAME" ] && [ "$((10#${BASH_REMATCH[2]}))" -gt "$LAST_PRE" ]; then
            LAST_PRE=$((10#${BASH_REMATCH[2]}))
        fi
    else
        continue
    fi
    if [ "$code" -gt "$MAX_CODE" ]; then MAX_CODE="$code" MAX_TAG="$tag"; fi
done <<< "$TAGS"

case "$RELEASE" in
    stable)
        CHANNEL=stable
        BUILD=999
        TAG="v$VERSION_NAME"
        EXISTS="$STABLE_EXISTS"
        ;;
    preview)
        if [ "$STABLE_EXISTS" = true ]; then
            fail "v$VERSION_NAME is already released: raise VERSION_NAME in fork.properties (develop) for previews of the next version"
        fi
        CHANNEL=preview
        BUILD=$((LAST_PRE + 1))
        if [ "$BUILD" -gt 998 ]; then
            fail "998 previews of $VERSION_NAME: release it or raise VERSION_NAME in fork.properties"
        fi
        TAG="v$VERSION_NAME-pre.$BUILD"
        EXISTS=false
        ;;
    *)
        fail "fork.properties: RELEASE must be 'preview' or 'stable', got '$RELEASE'"
        ;;
esac
CODE="$(version_code "$VERSION_NAME" "$BUILD")"

if [ "$CHECK" = false ]; then
    printf '%s\n' "version=$VERSION_NAME" "channel=$CHANNEL" "build=$BUILD" "tag=$TAG" "version_code=$CODE" "exists=$EXISTS"
    exit 0
fi

if [ "$EXISTS" = true ]; then
    fail "v$VERSION_NAME is already released: raise VERSION_NAME in fork.properties and set RELEASE=preview (or stable)"
fi
if [ "$CODE" -le "$MAX_CODE" ]; then
    fail "$TAG (versionCode $CODE) is not above the published $MAX_TAG (versionCode $MAX_CODE): Android would not install it as an update"
fi
summary "Release plan: merging into main publishes the $CHANNEL release \`$TAG\` (versionCode $CODE)."
if [ "$CHANNEL" = stable ] && ! bash "$ROOT/buildScript/release_notes.sh" "$VERSION_NAME" > /dev/null 2>&1; then
    echo "::warning::No '#### $VERSION_NAME' section in the changelog: the release notes will list the commits"
    summary "No \`#### $VERSION_NAME\` section in the changelog: the release notes will list the commits."
fi
