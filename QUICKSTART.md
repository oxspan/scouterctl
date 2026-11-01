# scouterctl 0.0.0 — Quick start

A CLI for querying observability data from a Scouter Collector and exporting it as JSON/JSONL files. This is an initial POC release; commands and output formats may change.

## Prerequisites

You need JDK 21 and access to a Scouter Collector running **2.21.3**. The Java runtime, Collector, and Agent are not included in the package. Extract the ZIP and start in the directory containing the executable JAR.

```sh
java -jar scouterctl-0.0.0-all.jar --version
java -jar scouterctl-0.0.0-all.jar --help
```

The version output is `scouterctl 0.0.0`. To verify the downloaded ZIP, place it alongside `SHA256SUMS` and run `sha256sum -c SHA256SUMS`.

## Connect and find a target

These examples use Bash. Replace `guest` with your Collector account. Supply the password through an environment variable.

```sh
read -r -s -p 'Collector password: ' SCOUTERCTL_PASSWORD
export SCOUTERCTL_PASSWORD

java -jar scouterctl-0.0.0-all.jar doctor --user guest
java -jar scouterctl-0.0.0-all.jar object list --user guest
```

The default address is `127.0.0.1:6100`. To connect to another server, add `--host` and `--port` to **each command**. The TCP connection has no built-in TLS; use a trusted network or an SSH/VPN tunnel.

## Query and export

Run these commands with the password environment variable set. Replace the example object hash, date, time zone, and transaction ID with values from your environment.

```sh
java -jar scouterctl-0.0.0-all.jar xlog search --user guest \
  --object-hash 42 --date 20261102 --timezone Asia/Seoul \
  --from 2026-11-02T09:00:00+09:00 --to 2026-11-02T09:10:00+09:00

java -jar scouterctl-0.0.0-all.jar profile get --user guest \
  --date 20261102 --txid 9223372036854775806

java -jar scouterctl-0.0.0-all.jar capture --user guest \
  --object-hash 42 --date 20261102 --timezone Asia/Seoul \
  --from 2026-11-02T09:00:00+09:00 --to 2026-11-02T09:10:00+09:00 \
  --directory ./incident-001
```

The destination directory must not exist for a new export. If the export is interrupted or partially completes, add `--resume` to the same capture command. Each query covers one date and one object over `[from,to)`. Set `--timezone` to the Collector's actual time zone.

Unset the password environment variable when you finish:

```sh
unset SCOUTERCTL_PASSWORD
```

## Output and support scope

- `doctor`, `object list`, `xlog search`, `profile get`, and `capture` (including `--resume`) are read-only.
- Results use JSON or JSONL, with 64-bit transaction IDs represented as strings. Help and version output use plain text.
- SQL steps include `hash`, `elapsedMs`, `errorHash`, and `updated`. To investigate application errors, inspect the XLog's `errorHash` and the profile for the same `txid`.
- SQL text, exception text, SQL bind values, and service name resolution are not available. Profile requests are limited to 1,000 steps; truncation detection is not supported.
- Capture completion indicates that the requested execution has finished, not that all traffic is included. Concurrent writes and durability during power loss are not guaranteed.
- Runtime validation has been performed on Linux/WSL. Other Collector versions, external databases, Windows, and Native Image builds are outside the validated scope.

Exit codes: `0` success, `2` invalid input/resume conditions, `3` authentication/session error, `5` connection failure/timeout, `6` protocol error, `7` profile not found, `8` partial completion, `9` version mismatch, `10` I/O error.

## Documentation

- [Changelog](CHANGELOG.md)
- [Scouter protocol library provenance](vendor/README.md)
