param(
    [Parameter(Mandatory)][string]$Archive,
    [Parameter(Mandatory)][string]$Version
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted' -or $env:RUNNER_OS -ne 'Windows') {
    throw 'Run this installation check on a disposable GitHub-hosted Windows runner.'
}
$archivePath = (Resolve-Path $Archive).Path
$work = Join-Path $env:GITHUB_WORKSPACE 'build/scoop-qa'
if (Test-Path $work) { throw 'Scoop verification requires a fresh directory.' }
New-Item -ItemType Directory -Path $work | Out-Null
Start-Transcript -Path "$work/scoop.log" | Out-Null
$userPath = [Environment]::GetEnvironmentVariable('PATH', 'User')
$userJava = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')

function Save-VerifiedArchive([string]$Url, [string]$Hash, [string]$Destination) {
    Invoke-WebRequest $Url -OutFile $Destination
    if ((Get-FileHash $Destination -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Hash) {
        throw "Archive checksum mismatch: $Url"
    }
}

function Invoke-Scoop([string[]]$Arguments) {
    & $script:scoopCommand @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Scoop failed: $Arguments" }
}

try {
    $env:SCOOP = "$work/scoop"
    $env:SCOOP_GLOBAL = "$work/global"
    $env:SCOOP_CACHE = "$work/cache"
    $env:XDG_CONFIG_HOME = "$work/config"
    $env:SCOUTERCTL_CONFIG = "$work/user-data/연결 설정.json"
    $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot\System32\WindowsPowerShell\v1.0;$env:SCOOP\shims"
    Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
    foreach ($name in @('JAVA_OPTS', 'SCOUTERCTL_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'SCOUTERCTL_PASSWORD')) {
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
    if (Get-Command java -ErrorAction SilentlyContinue) { throw 'Java must be absent from the initial PATH.' }

    Save-VerifiedArchive 'https://codeload.github.com/ScoopInstaller/Scoop/zip/e6aa3b366bdee8ed138c1e0f7b85192ebdd35d0f' 'fa96b15950f3726d2c9a35493e192eefe56d2eddba718e8121c32f7a19257a0a' "$work/scoop.zip"
    Save-VerifiedArchive 'https://codeload.github.com/ScoopInstaller/Java/zip/c2fcb00219a54d701791b1022f3b7b0b857dd5af' 'cfc8ac6b46b60eb5a78dd6a3d3541d4431435b2fc8b93ccf5cb69103af40e3ed' "$work/java.zip"
    Save-VerifiedArchive 'https://github.com/oxspan/scouterctl/releases/download/v1.0.0/scouterctl-1.0.0-windows-x64.zip' 'ede20898d4d936c87932d9cf1efa9d28571311c18ef741c53ce7de0b698aaac6' "$work/previous.zip"
    Expand-Archive "$work/scoop.zip" "$work/source"
    Expand-Archive "$work/java.zip" "$work/java-source"
    New-Item -ItemType Directory -Path "$env:SCOOP/apps/scoop", "$env:SCOOP/buckets", "$env:SCOOP/shims", "$env:XDG_CONFIG_HOME/scoop" | Out-Null
    Move-Item "$work/source/Scoop-e6aa3b366bdee8ed138c1e0f7b85192ebdd35d0f" "$env:SCOOP/apps/scoop/current"
    Move-Item "$work/java-source/Java-c2fcb00219a54d701791b1022f3b7b0b857dd5af" "$env:SCOOP/buckets/java"
    $bucket = New-Item -ItemType Directory -Path "$env:SCOOP/buckets/scouterctl/bucket"
    @{last_update = (Get-Date).ToString('o'); show_update_log = $false; use_sqlite_cache = $false} |
        ConvertTo-Json | Set-Content "$env:XDG_CONFIG_HOME/scoop/config.json" -Encoding utf8
    $script:scoopCommand = "$env:SCOOP/apps/scoop/current/bin/scoop.ps1"

    $manifest = Get-Content 'bucket/scouterctl.json' -Raw | ConvertFrom-Json
    $manifest.version = '1.0.0'
    $manifest.extract_dir = 'scouterctl-1.0.0'
    $manifest.architecture.'64bit'.url = ([uri]"$work/previous.zip").AbsoluteUri
    $manifest.architecture.'64bit'.hash = (Get-FileHash "$work/previous.zip" -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifest | ConvertTo-Json -Depth 10 | Set-Content "$bucket/scouterctl.json" -Encoding utf8
    Invoke-Scoop -Arguments @('install', 'scouterctl/scouterctl', '--no-update-scoop')
    $expectedJava = (Resolve-Path "$env:SCOOP/apps/temurin21-jre/current").Path
    if ([IO.Path]::GetFullPath($env:JAVA_HOME) -ine $expectedJava) { throw 'Scoop did not select its Java dependency.' }
    foreach ($app in @('scouterctl', 'temurin21-jre')) {
        if ((Get-Item "$env:SCOOP/apps/$app/current").LinkType -ne 'Junction') { throw "Missing default junction: $app" }
    }
    $cli = "$env:SCOOP/shims/scouterctl.exe"
    if ((& $cli --version) -cne 'scouterctl 1.0.0' -or $LASTEXITCODE -ne 0) { throw 'Previous Scoop package did not execute.' }
    & $cli context add --name '운영_서울' --user '관측 사용자'
    if ($LASTEXITCODE -ne 0) { throw 'Scoop package could not write configuration.' }
    & $cli context use --name '운영_서울'
    if ($LASTEXITCODE -ne 0) { throw 'Scoop package could not select a context.' }
    $saved = (Get-FileHash $env:SCOUTERCTL_CONFIG -Algorithm SHA256).Hash

    $manifest.version = $Version
    $manifest.extract_dir = "scouterctl-$Version"
    $manifest.architecture.'64bit'.url = ([uri]$archivePath).AbsoluteUri
    $manifest.architecture.'64bit'.hash = (Get-FileHash $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifest | ConvertTo-Json -Depth 10 | Set-Content "$bucket/scouterctl.json" -Encoding utf8
    Invoke-Scoop -Arguments @('update', 'scouterctl')
    if ((& $cli --version) -cne "scouterctl $Version" -or $LASTEXITCODE -ne 0) { throw 'Scoop upgrade did not execute the candidate.' }
    $junction = Get-Item "$env:SCOOP/apps/scouterctl/current"
    if ($junction.LinkType -ne 'Junction' -or (Split-Path $junction.Target -Leaf) -cne $Version) { throw 'Scoop current junction did not advance.' }
    $contexts = (& $cli context list) | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or $contexts.data.current -cne '운영_서울' -or $contexts.data.contexts.'운영_서울'.user -cne '관측 사용자') {
        throw 'Scoop upgrade lost the saved Unicode context.'
    }
    Invoke-Scoop -Arguments @('uninstall', 'scouterctl')
    if ((Test-Path $cli) -or (Get-FileHash $env:SCOUTERCTL_CONFIG -Algorithm SHA256).Hash -cne $saved) {
        throw 'Scoop uninstall left the command installed or changed user configuration.'
    }
    Invoke-Scoop -Arguments @('uninstall', 'temurin21-jre')
    'PASS: Scoop automatic Java dependency, default junctions, previous release -> candidate upgrade, Unicode configuration and uninstall'
} finally {
    [Environment]::SetEnvironmentVariable('PATH', $userPath, 'User')
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $userJava, 'User')
    Stop-Transcript | Out-Null
}
