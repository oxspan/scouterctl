# Install the command

The standard command distribution contains `bin/scouterctl` (Linux/macOS) and
a legacy Windows batch script. The Windows x64 distribution provides
`bin/scouterctl.exe`. Both contain dependencies and license notices and use an
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

Use `scouterctl-<version>-windows-x64.zip` for the native Windows command.
It requires Windows 10 version 1903 or newer on x64 and an x64 HotSpot JDK
21 or newer. The native command preserves Korean installation paths and
direct Unicode arguments without changing your system locale or code page.

```powershell
Expand-Archive -LiteralPath ./scouterctl-0.3.0-windows-x64.zip -DestinationPath "$HOME/도구"
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-21'
$env:PATH = "$HOME/도구/scouterctl-0.3.0/bin;$env:PATH"
scouterctl --version
scouterctl context add --name 운영_서울 --user guest
```

Use your actual JDK directory. For future terminals, add the installation's
`bin` directory to your **user** PATH in Windows settings. The package does
not change PATH, the registry, your console code page or execution policy.
`JAVA_HOME` takes priority over `java.exe` on PATH. PowerShell and Command
Prompt can both resolve `scouterctl.exe` from PATH.

Relative configuration and capture paths remain relative to your current
working directory. Unicode values can be passed directly, including emoji
in account names and capture paths. As with other native Windows programs,
use your shell's quoting rules; PowerShell 7 is recommended for values with
literal quote characters. Java `ProcessBuilder` callers should use
`-Djdk.lang.Process.allowAmbiguousCommands=false` in the calling JVM for
correct Windows escaping of literal quotes and backslashes.

JDK 21 still has a separate class-loading limitation for supplementary
characters such as emoji in **installation paths**. Keep those characters
out of the installation directory; Korean letters and spaces are supported.
The older `.bat` and direct `java -jar` launch methods retain the JDK
argument limitations described in [Quick start](../QUICKSTART.md#windows-unicode-paths-and-arguments).

The executable hosts Java in the same process using the public JNI
Invocation API. It does not ship a JDK or copy credentials into argument
files. `JAVA_OPTS` and `SCOUTERCTL_OPTS` accept JVM options; the JVM also reads
`JAVA_TOOL_OPTIONS`. The java.exe-specific `JDK_JAVA_OPTIONS` is rejected
with guidance to use `JAVA_TOOL_OPTIONS` instead.

### Build the Windows package

The optional native build uses [Zig 0.15.2](https://ziglang.org/download/) and
Windows JDK 21 headers. You can cross-compile from Linux or build on Windows:

```sh
./gradlew windowsDistZip -PzigHome=/path/to/zig-0.15.2 \
  -PwindowsJdkHome=/path/to/windows-jdk-21
```

On Windows use `gradlew.bat`; `windowsJdkHome` defaults to the JDK running
Gradle. The ordinary Kotlin build does not require Zig. The ZIP includes
notices for the native toolchain's linked runtime components.

Run the native launcher tests **on Windows**, against the resulting ZIP:

```powershell
./gradlew.bat test --tests dev.scouterctl.WindowsLauncherTest `
  -PwindowsDistribution=C:/packages/scouterctl-0.3.0-windows-x64.zip
```

## Java startup errors

If Java is absent or `JAVA_HOME` names a missing installation, the launcher
writes an error to stderr and exits with code `1`. The native Windows
command also rejects Java versions older than 21. The legacy Java launch
methods can report `UnsupportedClassVersionError`, also with code `1`. These startup errors are not CLI JSON responses. Select JDK 21 and
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
