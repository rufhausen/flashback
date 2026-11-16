#!/usr/bin/env bash
# Prints the CHANGELOG.md body for one version (the lines under "## [X.Y.Z]").
set -euo pipefail
awk -v v="$1" '
  $0 ~ "^## \\[" v "\\]" { on = 1; next }
  on && /^## \[/ { exit }
  on { print }
' "$(dirname "$0")/../CHANGELOG.md" | sed -e '/./,$!d'
