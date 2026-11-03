$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$version = (Get-Content version.txt -Raw).Trim()
if ($version -notmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$') { throw 'Invalid package version' }
$manifest = Get-Content .release-please-manifest.json -Raw | ConvertFrom-Json
if ($manifest.'.' -cne $version) { throw 'Version and manifest disagree' }
$prefix = "scouterctl-$version"
$archive = "build/portable-download/$prefix-poc.zip"
$hash = (Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant()
$expectedHash = (Get-Content build/portable-download/SHA256SUMS -Raw).Trim()
if ($expectedHash -cne "$hash  $prefix-poc.zip") { throw 'Package checksum mismatch' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $archive))
try {
    $actual = @($zip.Entries.FullName | Where-Object { -not $_.EndsWith('/') } | Sort-Object)
    $expected = @('README.md', 'CHANGELOG.md', 'version.txt', "$prefix-all.jar", 'vendor/README.md', 'vendor/SCOUTER-LICENSE.txt') |
        ForEach-Object { "$prefix/$_" } | Sort-Object
    if (Compare-Object $expected $actual -CaseSensitive) { throw 'Unexpected archive contents' }
} finally { $zip.Dispose() }

$destination = [IO.Path]::GetFullPath('build/portable install 한글')
Expand-Archive -LiteralPath $archive -DestinationPath $destination -Force
$package = Join-Path $destination $prefix
foreach ($pair in @(@('QUICKSTART.md', 'README.md'), @('version.txt', 'version.txt'))) {
    if ((Get-FileHash $pair[0]).Hash -ne (Get-FileHash (Join-Path $package $pair[1])).Hash) { throw 'Packaged metadata differs' }
}
$jar = Join-Path $package "$prefix-all.jar"
$actualVersion = & java -jar $jar --version
if ($LASTEXITCODE -ne 0 -or $actualVersion -cne "scouterctl $version") { throw 'Packaged version failed' }
& java -jar $jar --help | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Packaged help failed' }
if ($env:GITHUB_OUTPUT) { "cli_jar=$jar" | Out-File -FilePath $env:GITHUB_OUTPUT -Append -Encoding utf8 }
Write-Output "Verified $prefix on $([Runtime.InteropServices.RuntimeInformation]::OSDescription) / $([Runtime.InteropServices.RuntimeInformation]::OSArchitecture)"
Write-Output "ZIP SHA256: $hash"
