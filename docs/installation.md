# Install the command

The command distribution contains `bin/scouterctl` (Linux/macOS),
`bin/scouterctl.bat` (Windows), dependencies, and license notices. It uses an
installed JDK 21; it does not include Java, a Collector, or an Agent.

This distribution is currently built from source. Published POC releases still
use the executable JAR described in [Quick start](../QUICKSTART.md). No npm
package or system package is published yet.

## Build

With JDK 21 selected:

```sh
./gradlew test distZip
```

The output is `build/distributions/scouterctl-<version>.zip`. The build also
provides `distTar` and `installDist` for local use. Build on Windows with
`gradlew.bat test distZip`.

## Linux and macOS

Extract the ZIP into a user-owned directory, then add its `bin` directory to
PATH. For example, with version `0.3.0`:

```sh
mkdir -p "$HOME/.local/opt"
unzip scouterctl-0.3.0.zip -d "$HOME/.local/opt"
export PATH="$HOME/.local/opt/scouterctl-0.3.0/bin:$PATH"
scouterctl --version
scouterctl --help
```

The PATH change applies to this shell. Add the same `export` line to your
shell's startup file if you want it in future shells. If necessary, make the
launcher executable with `chmod +x <installation>/bin/scouterctl`.

Set `JAVA_HOME` to JDK 21, or make Java 21 available on PATH. The launcher
preserves your working directory, arguments, standard streams, and exit code.
It replaces itself with Java on Unix, so terminal interrupts reach the CLI.

## Windows

Extract the ZIP to a directory whose full path contains only ASCII characters,
such as `C:\Tools`. In PowerShell:

```powershell
Expand-Archive -LiteralPath ./scouterctl-0.3.0.zip -DestinationPath C:/Tools
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-21'
$env:PATH = "C:/Tools/scouterctl-0.3.0/bin;$env:PATH"
scouterctl --version
scouterctl --help
```

Use your actual JDK directory. For future terminals, add the installation's
`bin` directory to your **user** PATH in Windows settings. The package does
not change PATH, the registry, or your PowerShell execution policy.

The Windows JDK 21 launcher can replace characters outside the system code
page in installation paths and direct command arguments. The command package
does not solve that JDK limitation. For Korean arguments, use the UTF-8
argument-file approach in [Quick start](../QUICKSTART.md#windows-unicode-paths-and-arguments),
invoked as `scouterctl '@query.args'`. Keep the argument filename ASCII and
use an ASCII installation path. Paths inside that file are relative to your
current working directory. Passwords belong in the interactive prompt or
`SCOUTERCTL_PASSWORD`, not in argument files.

## Java startup errors

If Java is absent or `JAVA_HOME` names a missing installation, the launcher
writes an error to stderr and exits with code `1`. An older Java installation
can report `UnsupportedClassVersionError` before the CLI starts, also with
code `1`. These startup errors are not CLI JSON responses. Select JDK 21 and
retry the same command.

## Connect and extract data

Existing options work unchanged after installation:

```sh
scouterctl doctor --host 127.0.0.1 --port 6100 --user guest --interactive
scouterctl object list
scouterctl capture --help
scouterctl inspect --help
```

Only the first example supplies connection options; use a saved context or
provide your own options for subsequent online commands. See [Quick start](../QUICKSTART.md)
for authentication, transaction queries, and resumable captures.

## Upgrade, pin, and remove

Extract each version into a separate directory. Change the PATH entry to
select a version; switching it back selects the previous version. Check
`scouterctl --version` after changing PATH, and start a new terminal if your
shell cached the old command. Do not extract one version over another.

To remove a version, remove its PATH entry and delete only that installation
directory. The package does not store user configuration or captures there.
Keep your configuration file (`SCOUTERCTL_CONFIG`, or `~/.config/scouterctl/config.json`)
(`XDG_CONFIG_HOME` changes the default base directory)
and capture directories when upgrading or removing the command. A newer
capture/configuration format may require a matching CLI version; see
[capture compatibility](capture-compatibility.md).

Linux and native Windows validation are recorded separately. macOS execution
and an end-user package manager remain release work; generating a Unix
launcher alone does not establish macOS support.
