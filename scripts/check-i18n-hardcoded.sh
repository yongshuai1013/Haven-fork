#!/usr/bin/env bash
# Guard against the #210 regression class: hardcoded user-facing strings in
# Compose UI that bypass stringResource(). Android's built-in HardcodedText
# lint only inspects XML View layouts, not Compose Text()/named-args, so it
# never catches this. We flag the highest-signal, lowest-false-positive
# pattern: `title = "..."` / `subtitle = "..."` named arguments whose value
# is literal prose. Local `val title = ...` declarations and string
# interpolations (`= "$..."`) are excluded.
#
# A hit means: move the literal into <module>/src/main/res/values/strings.xml
# and reference it with stringResource(R.string.<key>) — or context.getString
# outside a @Composable.
set -euo pipefail
cd "$(dirname "$0")/.."

matches=$(grep -rEn '(^|[(,][[:space:]]*)(title|subtitle)[[:space:]]*=[[:space:]]*"[A-Za-z]' \
    --include='*.kt' app core feature 2>/dev/null \
    | grep -vE '/(test|androidTest)/' \
    | grep -vE '\bval[[:space:]]+(title|subtitle)[[:space:]]*=' \
    | grep -vE '=[[:space:]]*"\$' || true)

if [ -n "$matches" ]; then
  echo "✖ Hardcoded user-facing title=/subtitle= strings found (localize them — see #210):"
  echo "$matches"
  echo
  echo "Move each literal into <module>/src/main/res/values/strings.xml and reference it"
  echo "with stringResource(R.string.<key>) (or context.getString outside a @Composable)."
  exit 1
fi
echo "✓ No hardcoded title=/subtitle= UI strings."
