# scouterctl — Quick start

A CLI for querying observability data from a Scouter Collector and exporting it as JSON/JSONL files. This is an initial POC release; commands and output formats may change.

## Prerequisites

You need JDK 21 and access to a Scouter Collector running **2.21.3**. The Java runtime, Collector, and Agent are not included in the package. Check `java -version` first: an older runtime cannot start this JAR and cannot return the CLI's JSON errors.

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

The version output matches the included `version.txt`. Offline `inspect` needs only JDK 21 and a v2 capture, with no account or Collector connection.

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

## Connect and find a target

These examples use Bash. Obtain the Collector's TCP host, port, and an account from its administrator. The defaults are `127.0.0.1:6100`; a Web API URL or port is not the Collector TCP endpoint. Replace the three connection values below once. The array keeps the same endpoint/account for subsequent online commands in this shell; it contains no password.

```sh
SCOUTERCTL=(java -jar "$SCOUTERCTL_JAR" --host 127.0.0.1 --port 6100 --user guest)
read -r -s -p 'Collector password: ' SCOUTERCTL_PASSWORD
printf '\n'
export SCOUTERCTL_PASSWORD

"${SCOUTERCTL[@]}" doctor
"${SCOUTERCTL[@]}" object list
```

`doctor` returning `ok: true` and exit `0` confirms login and the supported Collector version. It does not confirm that an Agent is connected. `object list` returns `data[]`; choose the expected application with `alive: true` and copy its `objHash` for queries. An empty `data: []` with exit `0` is valid: confirm the Agent points to this Collector and is running, then repeat discovery.

This explicit-flag flow needs no saved settings. Missing password input returns JSON and exit `2`, including when stdin is redirected. The hidden password prompt above belongs to Bash. The optional saved-context flow below uses the CLI terminal prompt. The TCP connection has no built-in TLS; use a trusted network or an SSH/VPN tunnel.

### If connection or discovery fails

| Result | Next step |
| --- | --- |
| Exit `2`, `INVALID_ARGUMENT` | Supply `--user` and `SCOUTERCTL_PASSWORD`. Use a hostname/IP for `--host`, a port in 1..65535, and `--timeout-ms` in 1..60000. |
| Exit `3`, `AUTH_FAILED` | Check the account and password with the Collector administrator. Re-enter the password using hidden input. |
| Exit `5`, `CONNECTION_FAILED` | Check hostname resolution, the TCP port, Collector availability, and the network/tunnel. For a slow reachable Collector, adjust `--timeout-ms` (default 5000). |
| Exit `9`, `VERSION_MISMATCH` | This POC supports Collector 2.21.3. Confirm the endpoint and version; there is no compatibility override. |
| Exit `0`, empty object list | Login worked, but no targets were reported. Check the Agent's Collector address and startup logs. |

Messages suggest a next check, not a definitive network diagnosis. A timeout can occur after a connection was established. After correcting input or configuration, rerun `doctor`, then `object list`.

## Service summaries from a saved capture

```sh
java -jar "$SCOUTERCTL_JAR" inspect summary --directory ./capture --include-text
java -jar "$SCOUTERCTL_JAR" inspect summary --directory ./capture --sort elapsed --limit 10
java -jar "$SCOUTERCTL_JAR" inspect summary --directory ./capture --sort requests
# Use services[].slowest.txid or services[].slowestError.txid with the existing detail command:
java -jar "$SCOUTERCTL_JAR" inspect --directory ./capture --txid '<TXID>' --include-text
```

`inspect summary` scans a v2 capture offline and groups by XLog `serviceHash` within the capture's source/object/time window. Each service includes `requests`, `errors`, `errorRate` (a fraction from 0 to 1), `elapsedTotalMs`, `averageElapsedMs`, `maxElapsedMs`, missing-profile/name counts and representative requests. Errors mean nonzero XLog error hashes, not HTTP status codes. Durations describe entire requests, not SQL durations. Every captured XLog counts, including separate Servlet error dispatches; these counts are not necessarily the number of incoming HTTP requests.

`--sort errors` (default) orders by error count, then maximum duration, descending. `--sort elapsed` orders by maximum duration, then error count, descending. `--sort requests` orders by request count, then error count, descending. Ties use ascending numeric service hash. `--limit` returns the first 1..1000 services (default 20); global counts and per-service statistics still cover **all captured records**, not only the displayed services. No search filters are accepted, so the denominator cannot silently change. `truncated` describes the display limit, not missing capture data.

`slowest` is the longest captured request, and `slowestError` is the longest errored request (null when there are no errors). Ties use ascending end time and numeric transaction ID. IDs remain strings. Each representative includes its profile availability; an unavailable profile still contributes its XLog metrics but may not supply SQL/error details. Service names require stored text and `--include-text`; hashes work without text. Conflicting stored names for one hash produce `nameConflict: true` and a null name. Hash collisions cannot be reliably disambiguated by this POC.

The result explicitly says `basis: "captured-xlogs"` and preserves source, query, `upstreamCoverage`, `sampling`, capture completion and last failure. Unknown coverage/sampling remains unknown. These are **sample/capture statistics**, not whole-traffic rates or proof of capture completeness. An empty complete capture succeeds with no service rows. Incomplete paging, any missing profiles or a saved failure returns data with exit `8`. Invalid options return `2`; corrupt/duplicate records or negative durations return `10`. All pages are validated even when the display limit is reached.

The scan retains one page, duplicate-detection IDs and one accumulator per service. At more than 10,000 distinct services it returns a structured error with exit `8` and **no partial aggregate**; use a smaller capture. Concurrent bundle modification is unsupported. Percentiles, SQL-level ranking and comparisons across captures are outside this POC.

## Saved connections and terminal input

```sh
java -jar "$SCOUTERCTL_JAR" context add --name local --host 127.0.0.1 --port 6100 --user observer
java -jar "$SCOUTERCTL_JAR" context list
java -jar "$SCOUTERCTL_JAR" context use --name local
java -jar "$SCOUTERCTL_JAR" auth login
java -jar "$SCOUTERCTL_JAR" object list --interactive
```

`context add` saves the supplied host, port and account without connecting or selecting it. Names are unique; existing entries are not overwritten. `context use` selects an existing entry for subsequent commands. These local commands require explicit arguments and never prompt. `auth login` checks the selected Collector and credentials, then disconnects; it does **not** save a password or session. Each subsequent command needs `SCOUTERCTL_PASSWORD` or its own `--interactive` input.

Selection order is `--context NAME`, then the saved current context. Explicit `--host`, `--port` and `--user` override individual saved values; absent values fall back to `127.0.0.1:6100`, with a user required. Supplying all three endpoint flags without `--context` bypasses the config entirely. A misspelled context or malformed config fails rather than silently connecting elsewhere. Offline `inspect` does not read this config.

The file is `$XDG_CONFIG_HOME/scouterctl/config.json` (or `$HOME/.config/scouterctl/config.json`). Set `SCOUTERCTL_CONFIG` to use a different file. It contains a versioned JSON list of connection settings and the current selection; no passwords, password hashes or sessions. Writes use a lock and atomic replacement; a busy file returns exit `10`. New config files use owner-only permissions on POSIX filesystems. Back up the file before manual editing; removing an entry also requires clearing `current` if it selected that entry.

Existing commands remain noninteractive by default. `auth login` and `--interactive` allow hidden input only when Java has a real console; `--no-input` always disables it. `SCOUTERCTL_PASSWORD` takes precedence and is never echoed. Redirected input/output cannot trigger a prompt or supply the password through stdin: missing input returns one JSON error with exit `2`. Prompts go to stderr, results remain JSON on stdout. Empty input or terminal EOF (Ctrl+D on Linux/macOS, Ctrl+Z then Enter on Windows) cancels with exit `2`; Ctrl+C terminates without saving credentials. There is no automatic retry or credential cache.

For CI, use explicit endpoint flags or an isolated `SCOUTERCTL_CONFIG`, set the password through your secret mechanism, and pass `--no-input`. Contexts are local preferences, not an access-control boundary. TCP still has no native TLS.

## Query and export

Run these commands with the password environment variable set. Replace the example object hash, date, time zone, and transaction ID with values from your environment.

```sh
"${SCOUTERCTL[@]}" xlog search \
  --object-hash 42 --date 20261102 --timezone Asia/Seoul \
  --from 2026-11-02T09:00:00+09:00 --to 2026-11-02T09:10:00+09:00

"${SCOUTERCTL[@]}" profile get \
  --date 20261102 --txid 9223372036854775806

# Optional: include potentially sensitive service, SQL, and error dictionary text.
"${SCOUTERCTL[@]}" profile get \
  --date 20261102 --txid 9223372036854775806 --include-text

"${SCOUTERCTL[@]}" capture \
  --object-hash 42 --date 20261102 --timezone Asia/Seoul \
  --from 2026-11-02T09:00:00+09:00 --to 2026-11-02T09:10:00+09:00 \
  --directory ./incident-001
```

The destination directory must not exist for a new export. If the export is interrupted or partially completes, add `--resume` to the same capture command. Each query covers one date and one object over `[from,to)`. Set `--timezone` to the Collector's actual time zone.

To save profiles as well, add `--include-profiles` to capture. Add `--include-text` only when you intend to store potentially sensitive dictionary text. This creates a v2 bundle; the default page size is 25 and the maximum is 100. Resume with the same flags and query. Missing profiles are recorded and retried on resume; connection failures preserve earlier page checkpoints.

Profile results now include `completeness`: `truncated` means the Collector explicitly reported omitted blocks; `unknown` does not certify completeness. Known truncation makes `profile get` and `inspect` return exit 8 with the available data. See [Profile completeness](https://github.com/oxspan/scouterctl/blob/develop/docs/profile-completeness.md), including compatibility with older capture readers.

Inspect a saved transaction after disconnecting from the Collector:

```sh
unset SCOUTERCTL_PASSWORD
java -jar "$SCOUTERCTL_JAR" inspect --directory ./incident-profiles \
  --txid 9223372036854775806 --include-text
```

Replace the directory with the v2 bundle you created. Omit `--include-text` to exclude stored text from the output. Requesting text that was not stored returns `2`. Inspect verifies all referenced pages and returns `8` with data for partial captures or unavailable profiles. A missing transaction returns `7`; damaged files return `10`. It supports v2 only, with no implicit v1 migration.

To find a transaction ID first, search the same bundle offline:

```sh
java -jar "$SCOUTERCTL_JAR" inspect search --directory ./incident-profiles \
  --min-elapsed-ms 300 --limit 10
java -jar "$SCOUTERCTL_JAR" inspect search --directory ./incident-profiles --errors-only
java -jar "$SCOUTERCTL_JAR" inspect search --directory ./incident-profiles \
  --service-name '/checkout<GET>' --include-text
```

Results contain `records[].xlog.txid`; pass one to `inspect --txid` for details. All filters combine with AND. Service names match exactly and require stored text plus `--include-text`; use `--service-hash` without text. Unknown names are counted separately and do not match name filters. Results sort by request duration descending, end time ascending, then numeric txid ascending. `--errors-only` means a nonzero XLog error hash, not HTTP status or SQL error classification.

Search defaults to 20 results (limit 1..1000), scans and validates every referenced page, and summarizes all matches rather than only the displayed top N. `truncated` means the result limit omitted matches. Empty matches return an empty result. Partial captures, missing profiles or a saved failure return `8` with data; otherwise search returns `0`. No Collector account or connection is needed. Use `inspect summary` for service-level aggregation or `inspect compare` to compare two v2 bundles.

Unset the password environment variable when you finish:

```sh
unset SCOUTERCTL_PASSWORD
```

## Compare saved captures

```sh
java -jar "$SCOUTERCTL_JAR" inspect compare --before ./before --after ./after
java -jar "$SCOUTERCTL_JAR" inspect compare --before ./before --after ./after --include-text --limit 100
```

Both directories must contain v2 captures created with `capture --include-profiles`. The command reads every referenced page, needs no credentials or Collector connection, and leaves both captures unchanged. Use completed captures that are no longer being written.

Each service is matched by its numeric service hash. `before` and `after` contain the captured metrics; `delta` is **after minus before** for request/error counts, total/average/maximum request duration, and error-rate **percentage points**. For example, 25% to 75% produces `errorRatePercentagePoints: 50`, not a relative percent change. Durations are milliseconds for the entire request, not SQL time. No throughput normalization, percentiles, or automatic improvement/regression verdict is calculated.

`presence` is `both`, `before-only`, or `after-only`. A service seen on just one side has a null counterpart and null delta: absence from a capture does not prove that a service was added or removed. Known name conflicts within or between captures set `identityConflict` and suppress the delta, even when names are omitted from output. Unresolved names and undetected hash collisions can still make identity uncertain.

Source identity, query window, window duration, object hash, sampling, coverage, capture completion, and unavailable-profile counts are retained for each side. `warnings` calls out differing scopes, overlapping windows, partial captures and identity uncertainty. Matching identifiers do not prove equal workloads or complete traffic coverage; this remains a comparison of **captured XLogs**, including any error dispatches.

Output sorts by service hash ascending. `--limit` selects 1..1000 rows (default 20); totals and warnings cover all observed services. `truncated` describes only the output limit. Each input is limited to 1,000 services; exceeding that returns exit `8` with an error instead of comparing truncated aggregates. Partial captures, saved failures or unavailable profiles return data and exit `8`; intact complete captures return `0`. Scope warnings alone do not change the exit code, and exit `0` does not establish comparability. Corruption on either side returns an error without comparison data.

Names are omitted unless `--include-text` is set, which requires stored text in both captures. SQL and exception text are not included in the comparison. Use a returned representative transaction ID with `inspect --directory` on the corresponding capture for detail.

## Output and support scope

- `doctor`, `object list`, `xlog search`, `profile get`, and `capture` (including `--resume`) are read-only.
- Results use UTF-8 JSON or JSONL, with 64-bit transaction IDs represented as strings. Configure terminal and pipe readers to decode UTF-8. Help and version output use plain text.
- SQL steps include `hash`, `elapsedMs`, `errorHash`, and `updated`. To investigate application errors, inspect the XLog's `errorHash` and the profile for the same `txid`.
- Text is excluded by default. `profile get --include-text` adds `transaction.service`, `transaction.error`, `steps[].sql.statement`, and `steps[].sql.error`. Entries retain their hash and report `resolved` with text or `unresolved` with null text. A zero error hash has no error entry; an unavailable XLog sets `transaction.status` to `unavailable` while SQL resolution continues. Lookup uses the requested date; connection/protocol failures return a nonzero exit code.
- Opt-in text is returned as stored and may contain sensitive values, SQL literals, or exception details. Separate SQL bind fields and other free-text steps remain excluded. Profile requests are limited to 1,000 steps; truncation detection and method-name resolution are not supported.
- `--include-text` is supported by `profile get`, `capture --include-profiles`, and `inspect`. V2 pages contain XLogs, profile availability, profiles, and optional dictionary snapshots. Inspect uses only these stored values.
- Profile capture is limited to 10,000 pages and 16 MiB per page. A smaller page size may be needed for large profiles; changing the query requires a new capture. Resume may leave unreferenced page files, which are ignored. Checksums detect accidental corruption; the manifest is not signed.
- Capture completion indicates that the requested execution has finished, not that all traffic is included. Concurrent writes and durability during power loss are not guaranteed.
- Online commands require Collector 2.21.3; other versions are rejected. Run the packaged JAR with JDK 21. On Windows, use the documented relative-JAR and UTF-8 argument-file workflow for Korean paths and Unicode values.

Exit codes: `0` success, `2` invalid input/resume conditions, `3` authentication/session error, `5` connection failure/timeout, `6` protocol error, `7` profile/transaction not found, `8` partial completion or capture limit, `9` version mismatch, `10` I/O or integrity error.

## Documentation

- [Changelog](CHANGELOG.md)
- [Scouter protocol library provenance](vendor/README.md)
