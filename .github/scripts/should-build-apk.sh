#!/usr/bin/env bash
# Decides whether HEAD is worth a new APK, compared with the last built commit.
#
#   should-build-apk.sh [last-built-ref]
#
# Builds when any of these is true:
#   - nothing has been built yet
#   - a new commit is a fix, feature or performance change
#   - a new commit message contains [build]
#   - at least MIN_COMMITS new commits change code
#
# Translations, docs, store listings and CI files don't count as code.
# --cherry-pick drops commits whose patch was already built, so the nightly rebase,
# which rewrites every hash, doesn't make our own commits look new.
set -euo pipefail

MIN_COMMITS="${MIN_COMMITS:-2}"
NON_CODE='^(fastlane/|docs/|\.github/|app/src/main/res/values-|.*\.md$)'
IMPORTANT='^(fix|feat|perf)(\(.*\))?!?:|^(fix|fixed|fixes|fixing|feat)\b'

decide() {
    echo "$2"
    echo "build=$1" >> "${GITHUB_OUTPUT:-/dev/null}"
    echo "reason=$2" >> "${GITHUB_OUTPUT:-/dev/null}"
    exit 0
}

last="${1:-}"
if [ -z "$last" ] || ! git rev-parse --quiet --verify "$last^{commit}" >/dev/null; then
    decide true "No previous build."
fi

count=0
while read -r commit; do
    [ -n "$commit" ] || continue
    message="$(git log -1 --format=%B "$commit")"
    if grep -qF '[build]' <<<"$message"; then
        decide true "Requested by [build] in ${commit:0:8}."
    fi
    if ! git show --format= --name-only "$commit" | grep -qvE "$NON_CODE"; then
        continue
    fi
    subject="$(head -n1 <<<"$message")"
    if grep -qiE "$IMPORTANT" <<<"$subject"; then
        decide true "Fix or feature: $subject"
    fi
    count=$((count + 1))
done < <(git log --cherry-pick --right-only --no-merges --format=%H "$last...HEAD")

if [ "$count" -ge "$MIN_COMMITS" ]; then
    decide true "$count new code commits."
fi
decide false "Only $count new code commit(s); need $MIN_COMMITS or a fix or feature."
