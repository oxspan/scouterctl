# Install scouterctl

English | [한국어](ko/installation.md)

Download the ZIP for your platform and `SHA256SUMS` from the same [GitHub Release](https://github.com/oxspan/scouterctl/releases):

| Platform | Archive | Runtime |
| --- | --- | --- |
| Linux x64 | `scouterctl-<version>-linux-x64.zip` | JDK 21 |
| Windows x64 | `scouterctl-<version>-windows-x64.zip` | x64 HotSpot JDK 21, Windows 10 1903 or newer |

Java, Collector and Agent are not bundled. Collector/Agent JVM requirements are independent of the CLI; see [supported versions](supported-versions.md). macOS and ARM are outside the initial supported platform scope. For 0.x JAR packages, use the [legacy guide](legacy-jar.md).

## Linux

Verify the checksum before extraction. The examples use version 1.0.0:

```sh
sha256sum --check --ignore-missing SHA256SUMS
mkdir -p "$HOME/.local/opt"
unzip scouterctl-1.0.0-linux-x64.zip -d "$HOME/.local/opt"
export PATH="$HOME/.local/opt/scouterctl-1.0.0/bin:$PATH"
scouterctl --version
scouterctl --help
```

Add the PATH entry to your shell startup file for future terminals. Set `JAVA_HOME` to JDK 21, or make Java 21 available on PATH. ZIP extraction tools that discard executable permissions may require `chmod +x <installation>/bin/scouterctl`.

## Windows

Verify the matching checksum before extraction:

```powershell
$archive = Get-Item ./scouterctl-1.0.0-windows-x64.zip
$actual = (Get-FileHash $archive.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
if ("$actual  $($archive.Name)" -cnotin (Get-Content ./SHA256SUMS)) { throw 'Checksum mismatch' }
Expand-Archive -LiteralPath $archive.FullName -DestinationPath "$HOME/Tools"
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-21'
$env:PATH = "$HOME/Tools/scouterctl-1.0.0/bin;$env:PATH"
scouterctl --version
scouterctl --help
```

Use your actual JDK directory. For future terminals, add the installation's `bin` directory to your **user** PATH. The package does not change PATH, registry, system locale, console code page or execution policy. `JAVA_HOME` takes priority over Java on PATH. PowerShell and Command Prompt resolve `scouterctl.exe` directly.

The native launcher preserves Korean installation paths and direct Unicode arguments. Keep emoji and other supplementary characters out of the **installation** path because of a JDK 21 class-loading limitation. Unicode values, caller directories and capture/configuration paths can include emoji. Java `ProcessBuilder` callers should set `-Djdk.lang.Process.allowAmbiguousCommands=false` in the calling JVM to preserve literal quotes/backslashes. Use your shell's quoting rules; PowerShell 7 is preferable for complex literal quotes.

`JAVA_OPTS` and `SCOUTERCTL_OPTS` accept JVM options; the JVM reads `JAVA_TOOL_OPTIONS` too. The java.exe-specific `JDK_JAVA_OPTIONS` is rejected with guidance. Startup failures such as missing Java write stderr and exit 1 before CLI JSON output is available.

## Connect

```sh
scouterctl doctor --host 127.0.0.1 --port 6100 --user guest --interactive
scouterctl context add --name local --host 127.0.0.1 --port 6100 --user guest
scouterctl context use --name local
```

The account comes from your Collector administrator. Passwords are not saved; provide `SCOUTERCTL_PASSWORD` with `--no-input` for automation. Continue with [queries and export](../QUICKSTART.md).

## Pin, update, roll back and remove

Extract every version into its own directory. Change the PATH entry to select a version and check `scouterctl --version`; open a new terminal or clear the shell's command cache when needed. Switching back selects the previous version. Never extract a new version over an existing installation.

Remove a version by removing its PATH entry and deleting only that installation directory. Keep your configuration and saved data outside installation directories. Configuration uses `SCOUTERCTL_CONFIG`, or `~/.config/scouterctl/config.json` (`XDG_CONFIG_HOME` changes the base directory). Upgrades and removal preserve those external files. Older CLI releases may not read newer formats; see [capture compatibility](capture-compatibility.md) and [counter export](counter-export.md).

## Build from source

See [contributing](../CONTRIBUTING.md) for the Gradle build and development installation, or [releasing](releasing.md) for native command archives and toolchain requirements.
