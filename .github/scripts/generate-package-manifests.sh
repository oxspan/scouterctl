#!/usr/bin/env bash
set -euo pipefail
version=${1:?version required}
directory=${2:?verified release directory required}
destination=${3:?destination required}
[[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]
linux="scouterctl-$version-linux-x64.zip"
windows="scouterctl-$version-windows-x64.zip"
expected=$(cd "$directory" && sha256sum "$linux" "$windows")
[[ "$(cat "$directory/SHA256SUMS")" == "$expected" ]]
for name in "$linux" "$windows"; do
  [[ "$(unzip -p "$directory/$name" "scouterctl-$version/version.txt")" == "$version" ]]
done
linux_hash=$(printf '%s\n' "$expected" | head -1 | cut -d' ' -f1)
windows_hash=$(printf '%s\n' "$expected" | tail -1 | cut -d' ' -f1)
mkdir -p "$destination/Formula" "$destination/bucket"
sed -e "s/@VERSION@/$version/g" -e "s/@LINUX_SHA256@/$linux_hash/g" \
  packaging/homebrew.rb.in > "$destination/Formula/scouterctl.rb"
sed -e "s/@VERSION@/$version/g" -e "s/@WINDOWS_SHA256@/$windows_hash/g" \
  packaging/scoop.json.in > "$destination/bucket/scouterctl.json"
printf 'Generated Homebrew and Scoop definitions for %s\n' "$version"
