#!/usr/bin/env bash
set -euo pipefail

: "${RELEASE_TAG:?}" "${PACKAGE_VERSION:?}" "${PACKAGE_SHA:?}" "${GH_REPO:?}"
[[ "$RELEASE_TAG" == "v$PACKAGE_VERSION" ]]
[[ "$(gh api "repos/$GH_REPO/commits/$RELEASE_TAG" --jq .sha)" == "$PACKAGE_SHA" ]]
gh release view "$RELEASE_TAG" --json isDraft --jq 'select(.isDraft == false)' | grep -q .

cd build/distributions
sha256sum -c SHA256SUMS
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
gh release view "$RELEASE_TAG" --json assets --jq '.assets[].name' > "$work/assets"
for asset in "scouterctl-$PACKAGE_VERSION-poc.zip" SHA256SUMS; do
  if grep -Fxq "$asset" "$work/assets"; then
    gh release download "$RELEASE_TAG" --pattern "$asset" --dir "$work"
    cmp "$asset" "$work/$asset"
    printf 'Already uploaded and identical: %s\n' "$asset"
  else
    gh release upload "$RELEASE_TAG" "$asset"
    printf 'Uploaded: %s\n' "$asset"
  fi
done
