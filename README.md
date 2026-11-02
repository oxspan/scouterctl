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

## Requirements

- **JDK 21**
- **Scouter Collector 2.21.3** and an account with query access for online commands
- Network access to the Collector's TCP port

Runtime validation has been performed on Linux/WSL. Other Collector versions are rejected at connection time. Install the Collector and Agent separately.

## Getting started

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

The following examples use Bash. Supply the password through the `SCOUTERCTL_PASSWORD` environment variable.

```sh
SCOUTERCTL_JAR="build/libs/scouterctl-$(cat version.txt)-all.jar"
read -r -s -p 'Collector password: ' SCOUTERCTL_PASSWORD
export SCOUTERCTL_PASSWORD

java -jar "$SCOUTERCTL_JAR" doctor --user guest
java -jar "$SCOUTERCTL_JAR" object list --user guest
```

The default address is `127.0.0.1:6100`. To connect to another server, add `--host` and `--port` to each command. Replace `guest` with your Collector account.

### Query transactions and profiles

Use an object hash returned by `object list`. Replace the example date, time range, object hash, and transaction ID with values from your environment.

```sh
java -jar "$SCOUTERCTL_JAR" xlog search --user guest \
  --object-hash 42 --date 20261102 --timezone Asia/Seoul \
  --from 2026-11-02T09:00:00+09:00 --to 2026-11-02T09:10:00+09:00

java -jar "$SCOUTERCTL_JAR" profile get --user guest \
  --date 20261102 --txid 9223372036854775806
```

Profiles include step types and ordering, along with SQL hashes, elapsed times, error hashes, and execution results. Text is excluded by default. To resolve service names, SQL statements, and transaction/SQL errors from the Collector's dictionaries, opt in explicitly:

```sh
java -jar "$SCOUTERCTL_JAR" profile get --user guest \
  --date 20261102 --txid 9223372036854775806 --include-text
```

The result adds `transaction.service`, `transaction.error`, `steps[].sql.statement`, and `steps[].sql.error`. Each dictionary entry retains its `hash` and reports `status: "resolved"` with `text`, or `status: "unresolved"` with `text: null` when absent. A zero error hash means no recorded error and produces no error entry. If the transaction is unavailable but its profile exists, `transaction.status` is `"unavailable"`; SQL resolution still proceeds. Dictionary lookup uses the requested storage date. Connection and protocol failures return a nonzero exit code instead of being treated as missing text.

**Opt-in text may contain sensitive data.** SQL bind fields and other free-text profile steps remain excluded, but SQL literals and exception messages can contain application values. Text is returned as stored, without redaction. `--include-text` is supported by `profile get`, `capture --include-profiles`, and offline `inspect`.

### Export and resume

```sh
java -jar "$SCOUTERCTL_JAR" capture --user guest \
  --object-hash 42 --date 20261102 --timezone Asia/Seoul \
  --from 2026-11-02T09:00:00+09:00 --to 2026-11-02T09:10:00+09:00 \
  --directory ./incident-001
```

The destination directory must not exist when starting a new export. If an export is interrupted or partially completes with exit code `8`, add `--resume` to the same command with the same query range. Resume validates the manifest's source and query and checks SHA-256 integrity of page files. Multiple processes writing to the same directory are not supported.

### Capture profiles and inspect offline

```sh
java -jar "$SCOUTERCTL_JAR" capture --user guest \
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

Unset the password environment variable when you finish:

```sh
unset SCOUTERCTL_PASSWORD
```

## Output and query scope

- Regular queries return JSON. `xlog search` returns JSONL with one event per line: `start → record → complete`. On failure, it ends with an `error` event.
- `txid`, `gxid`, and `caller` are strings to preserve 64-bit precision.
- Each query covers one date and one object. The start time is inclusive and the end time is exclusive: `[from,to)`.
- `--timezone` must match the Collector's actual time zone.
- `executionComplete` indicates that the requested execution has finished. It does not detect collection gaps or sampling, and does not guarantee coverage of all traffic.
- The default page size is 1,000 records, with a maximum of 5,000. The default limit is 40 pages per execution.

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
- Offline inspect currently selects one transaction by ID. Offline search, aggregation, and comparison are not implemented.
- File durability during power loss, load from long-running exports, Windows execution, and Native Image builds have not been validated.
- Server configuration changes and administrative commands are not available.

## Documentation

- [Quick start](QUICKSTART.md)
- [Changelog](CHANGELOG.md)
- [Development and releases](CONTRIBUTING.md)
- [Scouter protocol library provenance](vendor/README.md)
