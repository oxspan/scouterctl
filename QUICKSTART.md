# scouterctl — Quick start

A CLI for querying observability data from a Scouter Collector and exporting it as JSON/JSONL files. This is an initial POC release; commands and output formats may change.

## Prerequisites

You need JDK 21 and access to a Scouter Collector running **2.21.3**. The Java runtime, Collector, and Agent are not included in the package. Check `java -version` first: an older runtime cannot start this JAR and cannot return the CLI's JSON errors.

Download one ZIP and its `SHA256SUMS` from the same release into an empty directory. Before extracting:

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

The CLI does not prompt or save credentials/connection settings. Missing input returns JSON and exit `2`, including when stdin is redirected. The hidden password prompt above belongs to Bash. The TCP connection has no built-in TLS; use a trusted network or an SSH/VPN tunnel.

### If connection or discovery fails

| Result | Next step |
| --- | --- |
| Exit `2`, `INVALID_ARGUMENT` | Supply `--user` and `SCOUTERCTL_PASSWORD`. Use a hostname/IP for `--host`, a port in 1..65535, and `--timeout-ms` in 1..60000. |
| Exit `3`, `AUTH_FAILED` | Check the account and password with the Collector administrator. Re-enter the password using hidden input. |
| Exit `5`, `CONNECTION_FAILED` | Check hostname resolution, the TCP port, Collector availability, and the network/tunnel. For a slow reachable Collector, adjust `--timeout-ms` (default 5000). |
| Exit `9`, `VERSION_MISMATCH` | This POC supports Collector 2.21.3. Confirm the endpoint and version; there is no compatibility override. |
| Exit `0`, empty object list | Login worked, but no targets were reported. Check the Agent's Collector address and startup logs. |

Messages suggest a next check, not a definitive network diagnosis. A timeout can occur after a connection was established. After correcting input or configuration, rerun `doctor`, then `object list`.

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

Search defaults to 20 results (limit 1..1000), scans and validates every referenced page, and summarizes all matches rather than only the displayed top N. `truncated` means the result limit omitted matches. Empty matches return an empty result. Partial captures, missing profiles or a saved failure return `8` with data; otherwise search returns `0`. No Collector account or connection is needed. Grouped aggregation and bundle comparison are not supported.

Unset the password environment variable when you finish:

```sh
unset SCOUTERCTL_PASSWORD
```

## Output and support scope

- `doctor`, `object list`, `xlog search`, `profile get`, and `capture` (including `--resume`) are read-only.
- Results use JSON or JSONL, with 64-bit transaction IDs represented as strings. Help and version output use plain text.
- SQL steps include `hash`, `elapsedMs`, `errorHash`, and `updated`. To investigate application errors, inspect the XLog's `errorHash` and the profile for the same `txid`.
- Text is excluded by default. `profile get --include-text` adds `transaction.service`, `transaction.error`, `steps[].sql.statement`, and `steps[].sql.error`. Entries retain their hash and report `resolved` with text or `unresolved` with null text. A zero error hash has no error entry; an unavailable XLog sets `transaction.status` to `unavailable` while SQL resolution continues. Lookup uses the requested date; connection/protocol failures return a nonzero exit code.
- Opt-in text is returned as stored and may contain sensitive values, SQL literals, or exception details. Separate SQL bind fields and other free-text steps remain excluded. Profile requests are limited to 1,000 steps; truncation detection and method-name resolution are not supported.
- `--include-text` is supported by `profile get`, `capture --include-profiles`, and `inspect`. V2 pages contain XLogs, profile availability, profiles, and optional dictionary snapshots. Inspect uses only these stored values.
- Profile capture is limited to 10,000 pages and 16 MiB per page. A smaller page size may be needed for large profiles; changing the query requires a new capture. Resume may leave unreferenced page files, which are ignored. Checksums detect accidental corruption; the manifest is not signed.
- Capture completion indicates that the requested execution has finished, not that all traffic is included. Concurrent writes and durability during power loss are not guaranteed.
- Runtime validation has been performed on Linux/WSL. Other Collector versions, external databases, Windows, and Native Image builds are outside the validated scope.

Exit codes: `0` success, `2` invalid input/resume conditions, `3` authentication/session error, `5` connection failure/timeout, `6` protocol error, `7` profile/transaction not found, `8` partial completion or capture limit, `9` version mismatch, `10` I/O or integrity error.

## Documentation

- [Changelog](CHANGELOG.md)
- [Scouter protocol library provenance](vendor/README.md)
