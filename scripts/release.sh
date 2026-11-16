#!/usr/bin/env bash
# Usage: scripts/release.sh 0.2.0
# Bumps version, stamps the CHANGELOG "Unreleased" section, commits and tags. You push.
set -euo pipefail
cd "$(dirname "$0")/.."

new="${1:?usage: scripts/release.sh X.Y.Z}"
[[ "$new" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Version must be X.Y.Z"; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo "Working tree not clean"; exit 1; }
git rev-parse "v$new" >/dev/null 2>&1 && { echo "Tag v$new already exists"; exit 1; }

unreleased=$(awk '/^## \[Unreleased\]/{on=1;next} on&&/^## \[/{exit} on' CHANGELOG.md | grep -c '[^[:space:]]' || true)
[[ "$unreleased" -gt 0 ]] || { echo "CHANGELOG 'Unreleased' section is empty — write some notes first"; exit 1; }

code=$(( $(grep '^flashback.versionCode=' gradle.properties | cut -d= -f2) + 1 ))
sed -i '' -e "s/^flashback.versionName=.*/flashback.versionName=$new/" \
          -e "s/^flashback.versionCode=.*/flashback.versionCode=$code/" gradle.properties
perl -0pi -e "s/^## \\[Unreleased\\]\n/## [Unreleased]\n\n## [$new] - $(date +%Y-%m-%d)\n/m" CHANGELOG.md

git add gradle.properties CHANGELOG.md
git commit -m "Release v$new"
git tag -a "v$new" -m "v$new"
echo "Tagged v$new (versionCode $code). Push with: git push origin main --follow-tags"
