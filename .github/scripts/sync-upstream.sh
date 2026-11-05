#!/usr/bin/env bash
# Keeps this fork's commits on top of upstream. Used by sync-upstream.yml.
#
#   sync-upstream.sh rebase            rebase HEAD onto upstream; never pushes
#   sync-upstream.sh publish <old>     back up <old>, then move the fork's branch to HEAD
#   sync-upstream.sh promote <branch>  publish a reviewed repair branch
#
# Results are written to $GITHUB_OUTPUT. `status` is one of up_to_date, rebased, conflict.
set -euo pipefail

UPSTREAM_URL="${UPSTREAM_URL:-https://github.com/recloudstream/cloudstream.git}"
BRANCH="${BRANCH:-master}"

output() { echo "$1=$2" >> "${GITHUB_OUTPUT:-/dev/null}"; }

fetch_upstream() {
    git remote get-url upstream >/dev/null 2>&1 || git remote add upstream "$UPSTREAM_URL"
    git fetch --quiet --no-tags upstream "+$BRANCH:refs/remotes/upstream/$BRANCH"
}

cmd_rebase() {
    fetch_upstream
    local old base
    old="$(git rev-parse HEAD)"
    base="$(git rev-parse "upstream/$BRANCH")"
    output old_head "$old"
    output upstream_head "$base"

    if git merge-base --is-ancestor "$base" HEAD; then
        echo "Already contains upstream ${base:0:8}."
        output status up_to_date
        return
    fi

    if git rebase "$base"; then
        echo "Rebased onto upstream ${base:0:8}; HEAD is now $(git rev-parse --short=8 HEAD)."
        output status rebased
        return
    fi

    local files commit
    files="$(git diff --name-only --diff-filter=U | paste -sd, -)"
    commit="$(git log -1 --format='%h %s' REBASE_HEAD)"
    git rebase --abort
    echo "Conflict while applying: $commit"
    echo "Conflicting files: $files"
    output status conflict
    output conflict_files "$files"
    output conflict_commit "$commit"
}

cmd_publish() {
    local old="${1:?usage: publish <old-head>}"
    local tag="backup/$BRANCH-$(date -u +%F)-${old:0:8}"
    git push --quiet origin "$old:refs/tags/$tag"
    # The lease refuses the push if anyone moved the branch since this run started.
    git push --force-with-lease="$BRANCH:$old" origin "HEAD:refs/heads/$BRANCH"
    echo "Published $(git rev-parse --short=8 HEAD); previous head saved as $tag."
}

cmd_promote() {
    local branch="${1:?usage: promote <branch>}"
    fetch_upstream
    git fetch --quiet --no-tags origin "+refs/heads/$branch:refs/remotes/origin/$branch"
    if ! git merge-base --is-ancestor "upstream/$BRANCH" "origin/$branch"; then
        echo "Refusing: $branch does not contain the latest upstream $BRANCH. Run the sync again." >&2
        exit 1
    fi
    local old
    old="$(git ls-remote origin "refs/heads/$BRANCH" | cut -f1)"
    git checkout --quiet --detach "origin/$branch"
    cmd_publish "$old"
}

case "${1:-}" in
    rebase) cmd_rebase ;;
    publish) cmd_publish "${2:-}" ;;
    promote) cmd_promote "${2:-}" ;;
    *) echo "usage: $0 {rebase|publish <old-head>|promote <branch>}" >&2; exit 2 ;;
esac
