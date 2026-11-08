# Legacy JAR packages

The 0.x distribution used a `*-poc.zip` containing an executable JAR. For current command ZIPs, follow [installation](installation.md).

The examples below target 0.3.0 and require JDK 21. Use the README included with an older release for its available commands and Collector versions; the current support matrix does not apply retroactively.

## Linux

Download `scouterctl-0.3.0-poc.zip` and its `SHA256SUMS` from the same [release](https://github.com/oxspan/scouterctl/releases/tag/v0.3.0):

```sh
sha256sum -c SHA256SUMS
unzip scouterctl-0.3.0-poc.zip
cd scouterctl-0.3.0
java -jar scouterctl-0.3.0-all.jar --version
java -jar scouterctl-0.3.0-all.jar --help
```

## Windows

Verify `Get-FileHash` against the release checksum, extract the ZIP and enter the extracted directory. Use the ASCII JAR filename relative to that directory:

```powershell
Set-Location -LiteralPath 'C:/Tools/scouterctl-0.3.0'
java -jar scouterctl-0.3.0-all.jar --version
```

For Unicode values, write an argument file as UTF-8 **without a BOM**. For example, in PowerShell 7:

```powershell
@'
context add
--name 운영
--host 127.0.0.1
--port 6100
--user 관측계정
'@ | Set-Content -LiteralPath query.args -Encoding utf8NoBOM
java -Dfile.encoding=UTF-8 -jar scouterctl-0.3.0-all.jar '@query.args'
```

Pass `@query.args` after the JAR filename so the CLI reads it. Quote values containing spaces, `#` or quotes; inside quoted values, escape `"` as `\"` and `\` as `\\`. Prefer forward slashes in Windows paths. Paths inside the file are relative to the caller directory. Keep passwords in the environment or terminal prompt, never in the file.

This avoids the JDK launcher's direct-argument character conversion. It does not add newer commands or capture formats to an older binary. The current native command package handles direct Unicode arguments; keep emoji out of its installation path.

## Upgrade

Install the current version in a separate directory and keep configuration/captures outside both installations. Earlier versions may not read newer context names or capture formats. Preserve originals and check [capture compatibility](capture-compatibility.md) before switching versions.
