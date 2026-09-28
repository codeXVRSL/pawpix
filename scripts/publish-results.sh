#!/usr/bin/env bash
# Force-pushes a folder of CI results (screenshots, logs) to a branch, so they can be browsed on
# GitHub or fetched with git:  scripts/publish-results.sh <dir> <branch> <token>
set -eu
DIR=$1; BRANCH=$2; TOKEN=$3
[ -d "$DIR" ] || { echo "no $DIR"; exit 0; }
cd "$DIR"
printf '# %s\n\nCommit %s, run %s/%s/actions/runs/%s\n' "$BRANCH" "${GITHUB_SHA:-local}" \
  "${GITHUB_SERVER_URL:-}" "${GITHUB_REPOSITORY:-}" "${GITHUB_RUN_ID:-}" > README.md
git init -q
git checkout -q -b results
git add -A
git -c user.name="PawPixel CI" -c user.email="ci@users.noreply.github.com" commit -qm "Results for ${GITHUB_SHA:-local}"
git push -qf "https://x-access-token:${TOKEN}@github.com/${GITHUB_REPOSITORY}.git" "results:refs/heads/${BRANCH}"
echo "Pushed results to ${BRANCH}"
