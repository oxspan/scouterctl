$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$version = '0.15.2'
$sha = '3a0ed1e8799a2f8ce2a6e6290a9ff22e6906f8227865911fb7ddedc3cc14cb0c'
$archive = Join-Path $env:RUNNER_TEMP 'zig-windows.zip'
Invoke-WebRequest "https://ziglang.org/download/$version/zig-x86_64-windows-$version.zip" -OutFile $archive
if ((Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant() -cne $sha) { throw 'Zig checksum mismatch' }
$destination = Join-Path $env:RUNNER_TEMP 'scouterctl-zig'
New-Item -ItemType Directory -Path $destination | Out-Null
& tar.exe -xf $archive -C $destination
if ($LASTEXITCODE -ne 0) { throw 'Zig extraction failed' }
$homePath = Join-Path $destination "zig-x86_64-windows-$version"
if ((& "$homePath/zig.exe" version) -cne $version) { throw 'Unexpected Zig version' }
"zig_home=$homePath" | Out-File -FilePath $env:GITHUB_OUTPUT -Append -Encoding utf8
