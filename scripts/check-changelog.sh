#!/usr/bin/env bash
# Release-notes gate + extractor for CHANGELOG.md.
#
# CHANGELOG.md carries one `## vX.Y.Z` section per release (newest first).
# A version bump that ships without notes is the failure this guards: the
# release workflow used to auto-generate a body, so a release with *no human
# description* still looked populated (just a "Full Changelog" line) and slipped
# through — five releases did exactly that (v5.60.0–v5.60.3, v5.61.0).
#
# Modes:
#   check            (default) assert the section for the CURRENT versionName
#                    (app/build.gradle.kts) exists and is non-empty. Wired into
#                    CI lint, so a version bump can't merge without its notes.
#   extract vX.Y.Z   print that section's body (heading stripped, blank lines
#                    trimmed) to stdout — release.yml uses it as the Release body.
#
# Headings may carry a title suffix: `## v5.61.0 — SPICE` matches version v5.61.0.
set -euo pipefail
cd "$(dirname "$0")/.."

CHANGELOG="CHANGELOG.md"
GRADLE="app/build.gradle.kts"

# Body of the `## <version>` section: everything from that heading to the next
# `## ` heading (or EOF), heading line excluded.
section_body() {
  awk -v v="$1" '
    /^## / {
      if (grab) exit
      h = substr($0, 4); split(h, a, /[ \t]/)
      if (a[1] == v) { grab = 1; next }
    }
    grab { print }
  ' "$CHANGELOG"
}

mode="${1:-check}"
case "$mode" in
  extract)
    version="${2:?usage: check-changelog.sh extract vX.Y.Z}"
    body="$(section_body "$version")"
    # trim leading & trailing blank lines
    body="$(printf '%s\n' "$body" | sed -e '/./,$!d' | tac | sed -e '/./,$!d' | tac)"
    if [ -z "$body" ]; then
      echo "✖ No CHANGELOG.md section for $version" >&2
      exit 1
    fi
    printf '%s\n' "$body"
    ;;
  check)
    [ -f "$CHANGELOG" ] || { echo "✖ $CHANGELOG is missing." >&2; exit 1; }
    vname="$(grep -oE 'versionName = "[^"]+"' "$GRADLE" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
    [ -n "$vname" ] || { echo "✖ couldn't read versionName from $GRADLE" >&2; exit 2; }
    tag="v$vname"
    if ! section_body "$tag" | grep -q '[^[:space:]]'; then
      echo "✖ CHANGELOG.md has no non-empty '## $tag' section." >&2
      echo "  Add release notes for $tag (newest first) before releasing." >&2
      exit 1
    fi
    echo "✓ CHANGELOG.md has notes for $tag."
    ;;
  *)
    echo "usage: check-changelog.sh [check | extract vX.Y.Z]" >&2
    exit 2
    ;;
esac
