#!/usr/bin/env bash
set -euo pipefail

version=$(cat version.txt)
[[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]
[[ "$(jq -r '.["."]' .release-please-manifest.json)" == "$version" ]]
[[ -z "${EXPECTED_TAG:-}" || "$EXPECTED_TAG" == "v$version" ]]

archive="build/distributions/scouterctl-$version-poc.zip"
(cd build/distributions && sha256sum -c SHA256SUMS)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
prefix="scouterctl-$version"
printf '%s\n' "$prefix/README.md" "$prefix/CHANGELOG.md" "$prefix/version.txt" \
  "$prefix/scouterctl-$version-all.jar" "$prefix/vendor/README.md" \
  "$prefix/vendor/SCOUTER-LICENSE.txt" "$prefix/LICENSE" "$prefix/SECURITY.md" | sort > "$work/expected"
unzip -Z1 "$archive" | sed '/\/$/d' | sort > "$work/actual"
diff -u "$work/expected" "$work/actual"
unzip -q "$archive" -d "$work/extracted"
package="$work/extracted/$prefix"
cmp QUICKSTART.md "$package/README.md"
cmp version.txt "$package/version.txt"
cmp LICENSE "$package/LICENSE"
cmp SECURITY.md "$package/SECURITY.md"
[[ "$(java -jar "$package/scouterctl-$version-all.jar" --version)" == "scouterctl $version" ]]
java -jar "$package/scouterctl-$version-all.jar" --help > /dev/null
printf 'Verified package %s\n' "$version"
