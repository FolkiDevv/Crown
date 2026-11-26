#!/bin/bash
# Crown (GitOps): the release that fork.properties declares — VERSION_NAME and RELEASE — and the tag it publishes.
# release.yml publishes the declaration when it reaches main; ci.yml checks it on every pull request.
#
#   release_plan.sh                     the working tree's declaration as key=value lines for $GITHUB_OUTPUT: version,
#                                       release, channel (stable/preview), build (999 / N), tag, version_code and
#                                       exists (the tag is already on origin). Fails on a malformed declaration and on
#                                       a preview of a version whose stable release is out.
#   release_plan.sh --check <base-ref>  pull request check: what merging HEAD into <base-ref> publishes. Fails when HEAD
#                                       declares a new release that cannot be published: its tag is already on origin,
#                                       the version is already released, or its versionCode is not above the base's.
#                                       Writes the plan to $GITHUB_STEP_SUMMARY when it is set.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

fail() {
    echo "::error::$*" >&2
    exit 1
}

# VERSION_NAME and RELEASE of fork.properties at <ref>, or of the working tree without one
read_declaration() {
    local text
    if [ -n "${1:-}" ]; then
        text="$(git -C "$ROOT" show "$1:fork.properties")"
    else
        text="$(cat "$ROOT/fork.properties")"
    fi
    VERSION_NAME="$(printf '%s\n' "$text" | sed -n 's/^VERSION_NAME=//p' | tr -d '\r[:space:]')"
    RELEASE="$(printf '%s\n' "$text" | sed -n 's/^RELEASE=//p' | tr -d '\r[:space:]')"
}

# CHANNEL, BUILD, TAG and CODE of VERSION_NAME + RELEASE; the versionCode formula is buildSrc Fork.kt's
plan() {
    [[ "$VERSION_NAME" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] ||
        fail "fork.properties: VERSION_NAME must be MAJOR.MINOR.PATCH, got '$VERSION_NAME'"
    local major=$((10#${BASH_REMATCH[1]})) minor=$((10#${BASH_REMATCH[2]})) patch=$((10#${BASH_REMATCH[3]}))
    if [ "$major" -gt 20 ] || [ "$minor" -gt 99 ] || [ "$patch" -gt 99 ]; then
        fail "fork.properties: VERSION_NAME $VERSION_NAME is out of range (MAJOR 0..20, MINOR/PATCH 0..99)"
    fi
    case "$RELEASE" in
        stable)
            CHANNEL=stable
            BUILD=999
            TAG="v$VERSION_NAME"
            ;;
        pre.*)
            BUILD="${RELEASE#pre.}"
            if ! [[ "$BUILD" =~ ^[1-9][0-9]{0,2}$ ]] || [ "$BUILD" -gt 998 ]; then
                fail "fork.properties: RELEASE pre.<N> needs N in 1..998, got '$RELEASE'"
            fi
            CHANNEL=preview
            TAG="v$VERSION_NAME-pre.$BUILD"
            ;;
        *)
            fail "fork.properties: RELEASE must be 'stable' or 'pre.<N>', got '$RELEASE'"
            ;;
    esac
    CODE=$((((major * 100 + minor) * 100 + patch) * 1000 + BUILD))
}

# true when the tag is on origin
tag_exists() {
    local status=0
    git -C "$ROOT" ls-remote --exit-code --tags origin "refs/tags/$1" > /dev/null || status=$?
    case "$status" in
        0) echo true ;;
        2) echo false ;;
        *) fail "git ls-remote origin failed ($status)" ;;
    esac
}

summary() {
    printf '%s\n' "$*"
    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then printf '%s\n' "$*" >> "$GITHUB_STEP_SUMMARY"; fi
}

if [ "${1:-}" != "--check" ]; then
    read_declaration
    plan
    EXISTS="$(tag_exists "$TAG")"
    if [ "$EXISTS" = false ] && [ "$CHANNEL" = preview ] && [ "$(tag_exists "v$VERSION_NAME")" = true ]; then
        fail "v$VERSION_NAME is already released: raise VERSION_NAME in fork.properties for previews of the next version"
    fi
    printf '%s\n' "version=$VERSION_NAME" "release=$RELEASE" "channel=$CHANNEL" "build=$BUILD" "tag=$TAG" \
        "version_code=$CODE" "exists=$EXISTS"
    exit 0
fi

BASE="${2:?usage: release_plan.sh --check <base-ref>}"
read_declaration "$BASE"
BASE_VERSION="$VERSION_NAME" BASE_RELEASE="$RELEASE"
BASE_CODE=
if [ -n "$BASE_RELEASE" ]; then
    (plan > /dev/null 2>&1) && plan && BASE_CODE="$CODE"
fi
read_declaration
plan

if [ "$VERSION_NAME" = "$BASE_VERSION" ] && [ "$RELEASE" = "$BASE_RELEASE" ]; then
    summary "Release plan: fork.properties still declares \`$TAG\`, merging publishes nothing."
    exit 0
fi
if [ "$(tag_exists "$TAG")" = true ]; then
    if [ -z "$BASE_RELEASE" ]; then
        summary "Release plan: fork.properties declares the published \`$TAG\`, merging publishes nothing."
        exit 0
    fi
    fail "$TAG is already published: declare a new release (RELEASE=pre.<N+1>, stable, or a higher VERSION_NAME)"
fi
if [ "$CHANNEL" = preview ] && [ "$(tag_exists "v$VERSION_NAME")" = true ]; then
    fail "v$VERSION_NAME is already released: raise VERSION_NAME in fork.properties for previews of the next version"
fi
if [ -n "$BASE_CODE" ] && [ "$CODE" -le "$BASE_CODE" ]; then
    fail "$TAG (versionCode $CODE) is not above the base's v$BASE_VERSION $BASE_RELEASE (versionCode $BASE_CODE): Android would not install it as an update"
fi
summary "Release plan: merging publishes the $CHANNEL release \`$TAG\` (versionCode $CODE)."
if [ "$CHANNEL" = stable ] && ! bash "$ROOT/buildScript/release_notes.sh" "$VERSION_NAME" > /dev/null 2>&1; then
    echo "::warning::No '#### $VERSION_NAME' section in the changelog: the release notes will list the commits"
    summary "No \`#### $VERSION_NAME\` section in the changelog: the release notes will list the commits."
fi
