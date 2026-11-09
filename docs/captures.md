# Capture and inspect transactions

Use `xlog search` for transaction records, `profile get` for one profile, and `capture` to save records for offline analysis. These examples assume a [selected connection](connections.md) and a password in `SCOUTERCTL_PASSWORD`. Replace dates, object hashes and transaction IDs with values from your Collector.

## Query and export

Run these commands with the password environment variable set. Replace the example object hash, date, time zone, and transaction ID with values from your environment.

Create the parent `captures` directory if it does not exist; each new capture needs its own unused subdirectory.

```sh
mkdir captures
scouterctl xlog search \
  --object-hash 42 --date 20261107 --timezone Asia/Seoul \
  --from 2026-11-07T09:00:00+09:00 --to 2026-11-07T09:10:00+09:00

scouterctl profile get \
  --date 20261107 --txid 9223372036854775806

# Optional: include potentially sensitive service, SQL, and error dictionary text.
scouterctl profile get \
  --date 20261107 --txid 9223372036854775806 --include-text

scouterctl capture \
  --object-hash 42 --date 20261107 --timezone Asia/Seoul \
  --from 2026-11-07T09:00:00+09:00 --to 2026-11-07T09:10:00+09:00 \
  --include-profiles --directory captures/incident-001
```

Add `--include-text` to `xlog search` to resolve service names and error text without downloading profiles. Error text can contain sensitive values, including bound values or values from existing database rows.

The destination directory must not exist for a new capture. If the export is interrupted or partially completes, add `--resume` to the same capture command. Each query covers one date and one object over `[from,to)`. Set `--timezone` to the Collector's actual time zone.

The example saves profiles in a v3 bundle. Add `--include-text` to the capture command only when you intend to store potentially sensitive service, SQL and error text; the default page size is 25 and the maximum is 100. Resume v3 with the same flags and query. Existing v2 bundles remain readable but cannot be resumed by this version; see [Capture compatibility](capture-compatibility.md). Missing profiles are recorded and retried on resume; connection failures preserve earlier page checkpoints. See [Capture recovery](capture-recovery.md) for interruption handling and the limits of recovery.

Live `profile get` steps include `startTimeMs` relative to the transaction start and the recorded `parent` step index when the Collector provides them. Compare step start times to identify gaps. `SOCKET`, `APICALL` and `APICALL2` steps also report `elapsedMs` and `errorHash` (zero means no recorded error). When `--include-text` is set, `target` can include the recorded address and socket port. Treat those addresses as sensitive. Capture files retain the existing v3 step layout.

Profile results include `completeness`: `truncated` means the Collector explicitly reported omitted blocks; `unknown` does not certify completeness. Known truncation makes `profile get` and `inspect` return exit 8 with the available data. See [Profile completeness](profile-completeness.md), including compatibility with older capture readers.

Inspect a saved transaction after disconnecting from the Collector:

```sh
unset SCOUTERCTL_PASSWORD
scouterctl inspect --directory captures/incident-001 \
  --txid 9223372036854775806
```

Use `--include-text` when the capture was created with stored text and you want to display it. Requesting text that was not stored returns `2`. Inspect verifies all referenced pages and returns `8` with data for partial captures or unavailable profiles. A missing transaction returns `7`; damaged files return `10`. It supports v2 and v3, with no implicit v1 migration.

To find a transaction ID first, search the same bundle offline:

```sh
scouterctl inspect search --directory captures/incident-001 \
  --min-elapsed-ms 300 --limit 10
scouterctl inspect search --directory captures/incident-001 --errors-only
# This filter requires a capture created with --include-text:
scouterctl inspect search --directory captures/incident-001 \
  --service-name '/checkout<GET>' --include-text
```

Results contain `records[].xlog.txid`; pass one to `inspect --txid` for details. All filters combine with AND. Service names match exactly and require stored text plus `--include-text`; use `--service-hash` without text. Unknown names are counted separately and do not match name filters. Results sort by request duration descending, end time ascending, then numeric txid ascending. `--errors-only` means a nonzero XLog error hash, not HTTP status or SQL error classification.

Search defaults to 20 results (limit 1..1000), scans and validates every referenced page, and summarizes all matches rather than only the displayed top N. `truncated` means the result limit omitted matches. Empty matches return an empty result. Partial captures, missing profiles or a saved failure return `8` with data; otherwise search returns `0`. No Collector account or connection is needed. Use `inspect summary` for service-level aggregation or `inspect compare` to compare two v2/v3 bundles.

Unset the password environment variable when you finish:

```sh
unset SCOUTERCTL_PASSWORD
```

## Service summaries from a saved capture

```sh
scouterctl inspect summary --directory captures/incident-001
scouterctl inspect summary --directory captures/incident-001 --sort elapsed --limit 10
scouterctl inspect summary --directory captures/incident-001 --sort requests
# Use services[].slowest.txid or services[].slowestError.txid with the existing detail command:
scouterctl inspect --directory captures/incident-001 --txid '<TXID>'
```

`inspect summary` scans a v2 or v3 capture offline and groups by XLog `serviceHash` within the capture's source/object/time window. Each service includes `requests`, `errors`, `errorRate` (a fraction from 0 to 1), `elapsedTotalMs`, `averageElapsedMs`, `maxElapsedMs`, missing-profile/name counts and representative requests. Errors mean nonzero XLog error hashes, not HTTP status codes. Durations describe entire requests, not SQL durations. Every captured XLog counts, including separate Servlet error dispatches; these counts are not necessarily the number of incoming HTTP requests.

`--sort errors` (default) orders by error count, then maximum duration, descending. `--sort elapsed` orders by maximum duration, then error count, descending. `--sort requests` orders by request count, then error count, descending. Ties use ascending numeric service hash. `--limit` returns the first 1..1000 services (default 20); global counts and per-service statistics still cover **all captured records**, not only the displayed services. No search filters are accepted, so the denominator cannot silently change. `truncated` describes the display limit, not missing capture data.

`slowest` is the longest captured request, and `slowestError` is the longest errored request (null when there are no errors). Ties use ascending end time and numeric transaction ID. IDs remain strings. Each representative includes its profile availability; an unavailable profile still contributes its XLog metrics but may not supply SQL/error details. Service names require stored text and `--include-text`; hashes work without text. Conflicting stored names for one hash produce `nameConflict: true` and a null name. Undetected hash collisions can still make service identity uncertain.

The result explicitly says `basis: "captured-xlogs"` and preserves source, query, `upstreamCoverage`, `sampling`, capture completion and last failure. Unknown coverage/sampling remains unknown. These are **sample/capture statistics**, not whole-traffic rates or proof of capture completeness. An empty complete capture succeeds with no service rows. Incomplete paging, any missing profiles or a saved failure returns data with exit `8`. Invalid options return `2`; corrupt/duplicate records or negative durations return `10`. All pages are validated even when the display limit is reached.

The scan retains one page, duplicate-detection IDs and one accumulator per service. At more than 10,000 distinct services it returns a structured error with exit `8` and **no partial aggregate**; use a smaller capture. Concurrent bundle modification is unsupported. Percentiles and SQL-level ranking are not available. Use `inspect compare` for comparisons between captures.

## Compare saved captures

```sh
scouterctl inspect compare --before captures/before --after captures/after
# Both inputs must contain stored text for this option:
scouterctl inspect compare --before captures/before --after captures/after --include-text --limit 100
```

Both directories must contain v2 or v3 captures created with `capture --include-profiles`. The command reads every referenced page, needs no credentials or Collector connection, and leaves both captures unchanged. Use completed captures that are no longer being written.

Each service is matched by its numeric service hash. `before` and `after` contain the captured metrics; `delta` is **after minus before** for request/error counts, total/average/maximum request duration, and error-rate **percentage points**. For example, 25% to 75% produces `errorRatePercentagePoints: 50`, not a relative percent change. Durations are milliseconds for the entire request, not SQL time. No throughput normalization, percentiles, or automatic improvement/regression verdict is calculated.

`presence` is `both`, `before-only`, or `after-only`. A service seen on just one side has a null counterpart and null delta: absence from a capture does not prove that a service was added or removed. Known name conflicts within or between captures set `identityConflict` and suppress the delta, even when names are omitted from output. Unresolved names and undetected hash collisions can still make identity uncertain.

Source identity, query window, window duration, object hash, sampling, coverage, capture completion, and unavailable-profile counts are retained for each side. `warnings` calls out differing scopes, overlapping windows, partial captures and identity uncertainty. Matching identifiers do not prove equal workloads or complete traffic coverage; this remains a comparison of **captured XLogs**, including any error dispatches.

Output sorts by service hash ascending. `--limit` selects 1..1000 rows (default 20); totals and warnings cover all observed services. `truncated` describes only the output limit. Each input is limited to 1,000 services; exceeding that returns exit `8` with an error instead of comparing truncated aggregates. Partial captures, saved failures or unavailable profiles return data and exit `8`; intact complete captures return `0`. Scope warnings alone do not change the exit code, and exit `0` does not establish comparability. Corruption on either side returns an error without comparison data.

Names are omitted unless `--include-text` is set, which requires stored text in both captures. SQL and exception text are not included in the comparison. Use a returned representative transaction ID with `inspect --directory` on the corresponding capture for detail.
