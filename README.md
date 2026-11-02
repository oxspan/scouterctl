# scouterctl

A CLI for querying observability data directly from a Scouter Collector and exporting it as JSON/JSONL. Find monitored applications, inspect transactions and profiles, and save data for a specific time range.

This project is an **early POC**. See [version.txt](version.txt) for the current source version. It provides read-only queries and exports. Commands and output formats may change in future versions.

## Features

| Command | Description |
| --- | --- |
| `doctor` | Check Collector connectivity, authentication, and version |
| `object list` | List monitored objects and their object hashes |
| `xlog search` | Query transactions within a time range |
| `profile get` | Retrieve profile steps and SQL metadata; optionally resolve service, SQL, and error text |
| `capture` | Save query results as JSONL files with a manifest |
| `capture --resume` | Resume an interrupted export from its saved checkpoint |
| `capture --include-profiles` | Save XLogs and profiles in an offline v2 bundle; optionally include dictionary text |
| `inspect` | Read a captured transaction without Collector access or credentials |
| `inspect search` | Find slow/error/service requests in a v2 bundle with bounded results and summary counts |
| `inspect summary` | Group captured requests by service with error and latency metrics and representative IDs |

## Requirements

- **JDK 21**
- **Scouter Collector 2.21.3** and an account with query access for online commands
- Network access to the Collector's TCP port

Runtime validation has been performed on Linux/WSL. Other Collector versions are rejected at connection time. Install the Collector and Agent separately.

## Getting started

### Use a release package

Download the ZIP and `SHA256SUMS` from the same [GitHub Release](https://github.com/oxspan/scouterctl/releases). Follow the [quick start](QUICKSTART.md) to verify/extract it, set the Collector TCP endpoint once, use saved contexts or hidden Bash password input, and run `doctor` followed by `object list`. The packaged README contains the same instructions.

`doctor` checks login and the supported version; a successful login does not imply that an Agent is connected. An empty object list is a successful result. The quick start includes recovery steps for missing input, DNS/TCP failures, rejected credentials, unsupported versions, and missing targets.

### Build from source

Set `JAVA_HOME` to JDK 21, then run:

```sh
./gradlew test fatJar
SCOUTERCTL_JAR="build/libs/scouterctl-$(cat version.txt)-all.jar"
java -jar "$SCOUTERCTL_JAR" --version
java -jar "$SCOUTERCTL_JAR" --help
```

The executable JAR includes its required libraries. Run `./gradlew pocRelease` to create a ZIP package and `SHA256SUMS` in `build/distributions/`. If you are starting from the ZIP package, see the [quick start](QUICKSTART.md).

### Connect and find a target

The following examples use Bash. Set the Collector TCP host, port, and account once; supply the password through `SCOUTERCTL_PASSWORD`. These explicit flags work without a saved context. Passwords and sessions are never saved by the CLI.

```sh
SCOUTERCTL_JAR="build/libs/scouterctl-$(cat version.txt)-all.jar"
SCOUTERCTL=(java -jar "$SCOUTERCTL_JAR" --host 127.0.0.1 --port 6100 --user guest)
read -r -s -p 'Collector password: ' SCOUTERCTL_PASSWORD
printf '\n'
export SCOUTERCTL_PASSWORD

"${SCOUTERCTL[@]}" doctor
"${SCOUTERCTL[@]}" object list
```

Set `--host`, `--port`, and `--user` in the array to your Collector's TCP endpoint and account. Every online example below reuses those values. For connection failures or an empty list, follow the [quick-start recovery steps](QUICKSTART.md#if-connection-or-discovery-fails).

### Query transactions and profiles

Use an object hash returned by `object list`. Replace the example date, time range, object hash, and transaction ID with values from your environment.

```sh
"${SCOUTERCTL[@]}" xlog search \
  --object-hash 42 --date 20261102 --timezone Asia/Seoul \
  --from 2026-11-02T09:00:00+09:00 --to 2026-11-02T09:10:00+09:00

"${SCOUTERCTL[@]}" profile get \
  --date 20261102 --txid 9223372036854775806
```

Profiles include step types and ordering, along with SQL hashes, elapsed times, error hashes, and execution results. Text is excluded by default. To resolve service names, SQL statements, and transaction/SQL errors from the Collector's dictionaries, opt in explicitly:

```sh
"${SCOUTERCTL[@]}" profile get \
  --date 20261102 --txid 9223372036854775806 --include-text
```

The result adds `transaction.service`, `transaction.error`, `steps[].sql.statement`, and `steps[].sql.error`. Each dictionary entry retains its `hash` and reports `status: "resolved"` with `text`, or `status: "unresolved"` with `text: null` when absent. A zero error hash means no recorded error and produces no error entry. If the transaction is unavailable but its profile exists, `transaction.status` is `"unavailable"`; SQL resolution still proceeds. Dictionary lookup uses the requested storage date. Connection and protocol failures return a nonzero exit code instead of being treated as missing text.

**Opt-in text may contain sensitive data.** SQL bind fields and other free-text profile steps remain excluded, but SQL literals and exception messages can contain application values. Text is returned as stored, without redaction. `--include-text` is supported by `profile get`, `capture --include-profiles`, and offline `inspect`.

### Export and resume

```sh
"${SCOUTERCTL[@]}" capture \
  --object-hash 42 --date 20261102 --timezone Asia/Seoul \
  --from 2026-11-02T09:00:00+09:00 --to 2026-11-02T09:10:00+09:00 \
  --directory ./incident-001
```

The destination directory must not exist when starting a new export. If an export is interrupted or partially completes with exit code `8`, add `--resume` to the same command with the same query range. Resume validates the manifest's source and query and checks SHA-256 integrity of page files. Multiple processes writing to the same directory are not supported.

### Capture profiles and inspect offline

```sh
"${SCOUTERCTL[@]}" capture \
  --object-hash 42 --date 20261102 --timezone Asia/Seoul \
  --from 2026-11-02T09:00:00+09:00 --to 2026-11-02T09:10:00+09:00 \
  --directory ./incident-profiles --include-profiles --include-text

unset SCOUTERCTL_PASSWORD
java -jar "$SCOUTERCTL_JAR" inspect --directory ./incident-profiles \
  --txid 9223372036854775806 --include-text
```

`inspect` requires only JDK 21 and the capture directory. It never connects to the Collector. Omit `--include-text` to suppress stored dictionary text in the output. Requesting text from a capture that did not store it fails with exit code `2`.

Profile capture uses `scouterctl.capture/v2`. Each page stores XLogs, profile availability, profiles, and optional resolved/unresolved dictionary snapshots embedded in the profiles. The manifest records the source, query, text policy, cursor, per-page checksums/counts, unavailable profile counts, and the last handled failure. Dictionary snapshots retain their capture-time values; they are not refreshed by inspect.

- Profile capture defaults to 25 transactions per page, with a maximum of 100, 10,000 pages per bundle, and 16 MiB per page file. If a page exceeds the byte limit, start a new capture with a smaller page size.
- A page is referenced by the manifest only after all of its records have been processed. Resume retries unavailable profiles and continues from the saved cursor. Keep the same source, query, page size, and text policy. `--max-pages` limits newly fetched XLog pages per run; retries of previously unavailable profiles are separate.
- Missing profiles are stored as `profileStatus: "unavailable"`. A profile response being available does not guarantee it contains every step. Transport/protocol failures stop the run without committing the current page; earlier committed pages remain inspectable.
- Capture returns `8` when pages remain or profiles are unavailable. Inspect also returns `8` with data when the capture is incomplete or has unavailable profiles. `captureExecutionComplete` describes XLog paging, while `unavailableProfiles` reports missing profiles. Dictionary entries marked `unresolved` remain distinct from unavailable profiles.
- Inspect validates all manifest-referenced pages before returning a transaction. Missing or corrupted files, unsafe page names, symlinks, inconsistent records, and duplicate transaction IDs are rejected. Checksums detect accidental damage; the manifest is not signed and this is not proof against deliberate tampering.
- Updated pages use content-derived filenames so repairing a missing profile does not overwrite the old checkpoint's page. Unreferenced files left by retries or interruption are ignored and not automatically removed. Writes are not guaranteed durable during power loss.
- Existing XLog-only captures remain v1. This POC's inspect supports v2 only; it does not migrate or overwrite a v1 capture.

### Find requests in a saved bundle

Search does not require a Collector connection or credentials:

```sh
# Slowest ten requests lasting at least 300 ms.
java -jar "$SCOUTERCTL_JAR" inspect search --directory ./incident-profiles \
  --min-elapsed-ms 300 --limit 10

# Requests with a nonzero XLog error hash, including those without a profile.
java -jar "$SCOUTERCTL_JAR" inspect search --directory ./incident-profiles --errors-only

# Exact captured service name; use the name returned by a text-enabled search.
java -jar "$SCOUTERCTL_JAR" inspect search --directory ./incident-profiles \
  --service-name '/checkout<GET>' --include-text
```

Use a returned `records[].xlog.txid` with `inspect --txid ...` for SQL/error details. Search returns XLog metadata and profile availability, not full profiles. `--include-text` adds stored service dictionary entries. `--service-hash` works without text; `--service-name` requires both stored text and `--include-text`. Name matching is exact and case-sensitive, with no regex or substring matching. Unknown names cannot match a name filter and are counted in `summary.unknownServiceNames` across the capture. Hashes are not collision-free identities.

Filters combine with AND. Results sort by request elapsed time descending, then end time ascending, then numeric transaction ID ascending. The default `--limit` is 20 (range 1..1000). `--min-elapsed-ms` is an inclusive, nonnegative request-duration threshold; it is not a SQL-duration filter. `--errors-only` uses the XLog error hash, not HTTP status or individual SQL errors.

All referenced pages are validated and scanned even after the result limit is reached. Search holds one page and at most `limit` result records, plus transaction IDs for duplicate detection. The summary counts captured records/errors/missing profiles and matching records/errors, with total and maximum matched request duration. Summary values cover all matches, not only the returned top N. `truncated` indicates that the output limit omitted matches; it does not make a completed capture partial.

An empty match set is a successful result, with zero matched total duration and null matched maximum. Partial paging, unavailable profiles, or a saved `lastFailure` returns data with exit `8`; otherwise search returns `0`. `captureExecutionComplete` describes captured paging, not traffic coverage. Source/query and unknown upstream coverage/sampling are preserved. Concurrent bundle modification is not supported. Search supports v2 only and does not accept `--txid` or `--resume`.

Unset the password environment variable when you finish:

```sh
unset SCOUTERCTL_PASSWORD
```

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

Existing commands remain noninteractive by default. `auth login` and `--interactive` allow hidden input only when Java has a real console; `--no-input` always disables it. `SCOUTERCTL_PASSWORD` takes precedence and is never echoed. Redirected input/output cannot trigger a prompt or supply the password through stdin: missing input returns one JSON error with exit `2`. Prompts go to stderr, results remain JSON on stdout. Empty input or terminal EOF cancels with exit `2`; Ctrl+C terminates without saving credentials. There is no automatic retry or credential cache.

For CI, use explicit endpoint flags or an isolated `SCOUTERCTL_CONFIG`, set the password through your secret mechanism, and pass `--no-input`. Contexts are local preferences, not an access-control boundary. TCP still has no native TLS.

## Output and query scope

- Regular queries return JSON. `xlog search` returns JSONL with one event per line: `start → record → complete`. On failure, it ends with an `error` event.
- `txid`, `gxid`, and `caller` are strings to preserve 64-bit precision.
- Each query covers one date and one object. The start time is inclusive and the end time is exclusive: `[from,to)`.
- `--timezone` must match the Collector's actual time zone.
- `executionComplete` indicates that the requested execution has finished. It does not detect collection gaps or sampling, and does not guarantee coverage of all traffic.
- Online XLog queries default to 1,000 records per page, with a maximum of 5,000. The default `--max-pages` is 40. Profile capture uses the smaller page limits described above; offline search uses `--limit` for returned records.

## Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Success |
| `2` | Invalid input or resume conditions |
| `3` | Authentication or session error |
| `5` | Connection failure or timeout |
| `6` | Protocol error |
| `7` | Profile or captured transaction not found |
| `8` | Partial capture/inspection, unavailable profiles, or capture limits reached |
| `9` | Unsupported Collector version |
| `10` | File or stream I/O error |

## Current limitations

- The Collector TCP connection has no built-in TLS. Use a trusted network or an SSH/VPN tunnel.
- Profile requests are limited to 1,000 steps. Truncation detection and method-name resolution are not supported yet. Text resolution depends on the Collector's retained dictionaries; hashes are not collision-free identities.
- Offline search supports request filters, top N and summary counts/durations. Service summaries group captured XLogs by service hash. Percentiles, SQL-level ranking and bundle comparison are not implemented.
- File durability during power loss, load from long-running exports, Windows execution, and Native Image builds have not been validated.
- Server configuration changes and administrative commands are not available.

## Documentation

- [Quick start](QUICKSTART.md)
- [Changelog](CHANGELOG.md)
- [Development and releases](CONTRIBUTING.md)
- [Scouter protocol library provenance](vendor/README.md)
