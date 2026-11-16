#!/usr/bin/env bash
set -euo pipefail
: "${GH_REPO:?}" "${RELEASE_TAG:?}" "${PACKAGE_VERSION:?}"
[[ "$GH_REPO" == "oxspan/scouterctl" ]]
[[ "$RELEASE_TAG" == "v$PACKAGE_VERSION" ]]
[[ "$PACKAGE_VERSION" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]
bash .github/scripts/generate-package-manifests.sh "$PACKAGE_VERSION" build/release build/package-index
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
base=$(gh api "repos/$GH_REPO" --jq .default_branch)
sha=$(gh api "repos/$GH_REPO/git/ref/heads/$base" --jq .object.sha)
gh api "repos/$GH_REPO/contents/bucket/scouterctl.json?ref=$sha" --jq .content | base64 --decode > "$work/current.json"
current=$(jq -r .version "$work/current.json")
if [[ "$(printf '%s\n' "$current" "$PACKAGE_VERSION" | sort -V | tail -1)" != "$PACKAGE_VERSION" ]]; then
  printf 'Skipping older package index %s (current %s)\n' "$PACKAGE_VERSION" "$current"
  exit 0
fi
gh api "repos/$GH_REPO/contents/Formula/scouterctl.rb?ref=$sha" --jq .content | base64 --decode > "$work/current.rb"
if cmp -s "$work/current.json" build/package-index/bucket/scouterctl.json && cmp -s "$work/current.rb" build/package-index/Formula/scouterctl.rb; then
  printf 'Package index already current\n'
  exit 0
fi
branch="automation/packages-v$PACKAGE_VERSION"
if [[ -n "$(gh pr list --repo "$GH_REPO" --head "$branch" --state open --json number --jq '.[].number')" ]]; then
  printf 'Package index pull request already open\n'
  exit 0
fi
if gh api "repos/$GH_REPO/git/ref/heads/$branch" > /dev/null 2>&1; then
  gh api "repos/$GH_REPO/contents/bucket/scouterctl.json?ref=$branch" --jq .content | base64 --decode > "$work/branch.json"
  gh api "repos/$GH_REPO/contents/Formula/scouterctl.rb?ref=$branch" --jq .content | base64 --decode > "$work/branch.rb"
  cmp "$work/branch.json" build/package-index/bucket/scouterctl.json
  cmp "$work/branch.rb" build/package-index/Formula/scouterctl.rb
else
  base_tree=$(gh api "repos/$GH_REPO/git/commits/$sha" --jq .tree.sha)
  jq -n --arg base "$base_tree" --rawfile formula build/package-index/Formula/scouterctl.rb \
    --rawfile scoop build/package-index/bucket/scouterctl.json \
    '{base_tree:$base,tree:[{path:"Formula/scouterctl.rb",mode:"100644",type:"blob",content:$formula},{path:"bucket/scouterctl.json",mode:"100644",type:"blob",content:$scoop}]}' > "$work/tree.json"
  tree=$(gh api --method POST "repos/$GH_REPO/git/trees" --input "$work/tree.json" --jq .sha)
  jq -n --arg tree "$tree" --arg parent "$sha" --arg version "$PACKAGE_VERSION" \
    '{message:("chore: update package managers to " + $version),tree:$tree,parents:[$parent]}' > "$work/commit.json"
  commit=$(gh api --method POST "repos/$GH_REPO/git/commits" --input "$work/commit.json" --jq .sha)
  gh api --method POST "repos/$GH_REPO/git/refs" -f ref="refs/heads/$branch" -f sha="$commit" > /dev/null
fi
printf 'Update Homebrew and Scoop to %s using the verified release checksums. Run CI and release manually on this branch before merging; pull requests created with GITHUB_TOKEN do not trigger pull_request workflows.\n' "$PACKAGE_VERSION" > "$work/body.md"
gh pr create --repo "$GH_REPO" --base "$base" --head "$branch" \
  --title "chore: update package managers to $PACKAGE_VERSION" --body-file "$work/body.md"
