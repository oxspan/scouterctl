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

## Requirements

- **JDK 21**
- **Scouter Collector 2.21.3** and an account with query access
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

**Opt-in text may contain sensitive data.** SQL bind fields and other free-text profile steps remain excluded, but SQL literals and exception messages can contain application values. Text is returned as stored, without redaction. `--include-text` is supported only by `profile get`; XLog searches and captures retain their existing metadata-only format.

### Export and resume

```sh
java -jar "$SCOUTERCTL_JAR" capture --user guest \
  --object-hash 42 --date 20261102 --timezone Asia/Seoul \
  --from 2026-11-02T09:00:00+09:00 --to 2026-11-02T09:10:00+09:00 \
  --directory ./incident-001
```

The destination directory must not exist when starting a new export. If an export is interrupted or partially completes with exit code `8`, add `--resume` to the same command with the same query range. Resume checks SHA-256 integrity for the page files and manifest. Multiple processes writing to the same directory are not supported.

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
| `7` | Profile not found |
| `8` | Partial completion due to the page limit |
| `9` | Unsupported Collector version |
| `10` | File or stream I/O error |

## Current limitations

- The Collector TCP connection has no built-in TLS. Use a trusted network or an SSH/VPN tunnel.
- Profile requests are limited to 1,000 steps. Truncation detection and method-name resolution are not supported yet. Text resolution depends on the Collector's retained dictionaries; hashes are not collision-free identities.
- Captures contain XLogs and a manifest; profiles and dictionaries are not included yet.
- File durability during power loss, load from long-running exports, Windows execution, and Native Image builds have not been validated.
- Server configuration changes and administrative commands are not available.

## Documentation

- [Quick start](QUICKSTART.md)
- [Changelog](CHANGELOG.md)
- [Development and releases](CONTRIBUTING.md)
- [Scouter protocol library provenance](vendor/README.md)
