#!/usr/bin/env bash
set -euo pipefail
version=${PACKAGE_VERSION:-$(cat version.txt)}
[[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]
if [[ ${ALLOW_CANDIDATE:-false} != true ]]; then
  [[ "$version" == "$(cat version.txt)" ]]
  [[ "$(jq -r '.["."]' .release-please-manifest.json)" == "$version" ]]
fi
[[ -z ${EXPECTED_TAG:-} || "$EXPECTED_TAG" == "v$version" ]]
cd build/release
linux="scouterctl-$version-linux-x64.zip"
windows="scouterctl-$version-windows-x64.zip"
cmp -s SHA256SUMS <(sha256sum "$linux" "$windows")
for archive in "$linux" "$windows"; do
  [[ "$(unzip -p "$archive" "scouterctl-$version/version.txt")" == "$version" ]]
done
printf 'Verified command release files %s\n' "$version"
