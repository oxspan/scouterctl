#!/usr/bin/env bash
set -euo pipefail
: "${RELEASE_TAG:?}" "${PACKAGE_VERSION:?}" "${PACKAGE_SHA:?}" "${GH_REPO:?}"
# Local version overrides are never publication authority.
[[ "$RELEASE_TAG" == "v$PACKAGE_VERSION" ]]
ALLOW_CANDIDATE=false bash .github/scripts/verify-release-files.sh
[[ "$(gh api "repos/$GH_REPO/commits/$RELEASE_TAG" --jq .sha)" == "$PACKAGE_SHA" ]]
gh release view "$RELEASE_TAG" --json isDraft --jq 'select(.isDraft == false)' | grep -q .
cd build/release
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
gh release view "$RELEASE_TAG" --json assets --jq '.assets[].name' > "$work/assets"
# Validate all existing assets before uploading any missing asset.
for asset in "scouterctl-$PACKAGE_VERSION-linux-x64.zip" "scouterctl-$PACKAGE_VERSION-windows-x64.zip" SHA256SUMS; do
  if grep -Fxq "$asset" "$work/assets"; then
    gh release download "$RELEASE_TAG" --pattern "$asset" --dir "$work"
    cmp "$asset" "$work/$asset"
  fi
done
for asset in "scouterctl-$PACKAGE_VERSION-linux-x64.zip" "scouterctl-$PACKAGE_VERSION-windows-x64.zip" SHA256SUMS; do
  if grep -Fxq "$asset" "$work/assets"; then
    printf 'Already uploaded and identical: %s\n' "$asset"
  else
    gh release upload "$RELEASE_TAG" "$asset"
    printf 'Uploaded: %s\n' "$asset"
  fi
done
