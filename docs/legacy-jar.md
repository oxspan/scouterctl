# Legacy 0.x JAR packages

These instructions apply to published 0.x POC assets. For command packages, use [installation](installation.md).

## Prerequisites

You need JDK 21 and access to a [supported Scouter Collector release](https://github.com/oxspan/scouterctl/blob/develop/docs/supported-versions.md). The Java runtime, Collector, and Agent are not included in the package. Check `java -version` first: an older runtime cannot start this JAR and cannot return the CLI's JSON errors.

Download one ZIP and its `SHA256SUMS` from the same release into an empty directory.

On Linux:

```sh
sha256sum -c SHA256SUMS
unzip scouterctl-*-poc.zip
cd scouterctl-*/
```

Start in the extracted directory containing the executable JAR:

```sh
SCOUTERCTL_JAR="scouterctl-$(cat version.txt)-all.jar"
java -jar "$SCOUTERCTL_JAR" --version
java -jar "$SCOUTERCTL_JAR" --help
```

The version output matches the included `version.txt`. Offline `inspect` needs only JDK 21 and a v2 or v3 capture, with no account or Collector connection.

On macOS, use `shasum -a 256 -c SHA256SUMS` instead of `sha256sum -c SHA256SUMS`, then use the same unzip and Bash commands.

On Windows with PowerShell 7:

Start inside the extracted directory and use the JAR filename as shown below. This also supports Korean installation directories. For non-ASCII command arguments, use the UTF-8 argument-file example below. Unicode text inside captures and JSON output is preserved.

```powershell
$archive = Get-Item ./scouterctl-*-poc.zip
$expected = (Get-Content ./SHA256SUMS -Raw).Trim()
$actual = (Get-FileHash $archive.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
if ($expected -cne "$actual  $($archive.Name)") { throw 'Checksum mismatch' }
Expand-Archive -LiteralPath $archive.FullName -DestinationPath .
Set-Location $archive.Name.Replace('-poc.zip', '')
$SCOUTERCTL_JAR = "scouterctl-$((Get-Content version.txt -Raw).Trim())-all.jar"
java -jar $SCOUTERCTL_JAR --version
java -jar $SCOUTERCTL_JAR --help
```

For online commands in PowerShell, set the password with `$env:SCOUTERCTL_PASSWORD = Read-Host 'Collector password' -MaskInput`, then run `java -jar $SCOUTERCTL_JAR --host 127.0.0.1 --port 6100 --user guest doctor` with your connection values. Clear the environment value afterward with `Remove-Item Env:SCOUTERCTL_PASSWORD`. Replace the Bash array invocation in later examples with `java -jar $SCOUTERCTL_JAR` and the same connection flags. Passwords are not stored in the CLI configuration.

### Windows Unicode paths and arguments

The [native Windows command package](installation.md#windows) supports
Korean installation paths and direct Unicode arguments as `scouterctl`.
The following workaround applies to the legacy JAR and batch launch methods.

The Windows JDK 21 launcher can replace characters outside the system code page before scouterctl starts. Changing only the console code page or `file.encoding` does not repair direct arguments. Use `Set-Location -LiteralPath` to enter the installation directory and pass only the ASCII JAR filename, not its full Korean path.

For Korean connection names, accounts, capture paths or service filters, save the arguments in a UTF-8 file **without a BOM**, then pass `@query.args` **after** the JAR filename. This file is read by scouterctl's argument parser, not the Java launcher:

```powershell
Set-Location -LiteralPath 'C:/도구/scouterctl'
$SCOUTERCTL_JAR = "scouterctl-$((Get-Content version.txt -Raw).Trim())-all.jar"
@'
inspect search
--directory "C:/관측 자료/캡처"
--service-name "/결제<GET>"
--include-text
'@ | Set-Content -LiteralPath query.args -Encoding utf8NoBOM
java -Dfile.encoding=UTF-8 -jar $SCOUTERCTL_JAR '@query.args'
```

Quote values containing spaces, `#` or quotes; inside quoted values, escape `"` as `\"` and `\` as `\\`. Forward slashes avoid Windows path escaping. Paths inside the file are relative to the shell's current directory, not the argument file. Keep the JAR and argument filenames ASCII. Keep passwords in `SCOUTERCTL_PASSWORD` or the interactive prompt, not in argument files.

The same file can contain `context add --name 운영 --user 관측계정`, followed by a separate invocation of `context use --name 운영`. Context names accept 1–64 Unicode code points: letters, decimal digits, combining marks, dots, underscores and hyphens, starting with a letter or digit. Names are stored exactly as entered, without case folding or Unicode normalization; visually identical spellings with different code points are distinct. Older versions that only accept ASCII names cannot read a config containing Unicode names; use a separate `SCOUTERCTL_CONFIG` file if switching back.

This workflow covers Korean installation folders and Unicode values read from argument files. Direct Unicode launcher arguments and installation paths containing emoji remain outside the supported Windows scope. See the [JDK launcher change](https://www.oracle.com/java/technologies/javase/21-0-9-relnotes.html) and [argument-file syntax](https://picocli.info/#AtFiles).
