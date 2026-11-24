#!/bin/bash
set -euo pipefail

USAGE="usage: release_notes.sh <version>"
VERSION="${1:?$USAGE}"
VERSION="${VERSION#v}"
# Crown: the fork's changelog (fork.properties CHANGELOG), unless the CHANGELOG environment variable names another file
if [ -z "${CHANGELOG:-}" ]; then
    CHANGELOG="$(dirname "$0")/../$(sed -n 's/^CHANGELOG=//p' "$(dirname "$0")/../fork.properties" | tr -d '\r[:space:]')"
fi

NOTES="$(awk -v version="$VERSION" '
    { sub(/\r$/, "") }
    /^#### / {
        if (found) exit
        heading = substr($0, 6)
        gsub(/^[ \t]+|[ \t]+$/, "", heading)
        found = heading == version
        next
    }
    found && (started || NF) { started = 1; print }
' "$CHANGELOG")"

if [ -z "${NOTES//[[:space:]]/}" ]; then
    echo "$(basename "$CHANGELOG") has no notes for $VERSION; add a '#### $VERSION' section at the top." >&2
    exit 1
fi
printf '%s\n' "$NOTES"
