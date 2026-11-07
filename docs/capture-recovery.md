# Capture recovery

Capture commits one complete page at a time. If the connection fails while reading the next XLog page or its profiles, the manifest retains the last committed cursor. Resume the same directory with the original endpoint, object, date, time range, page size, and profile/text options:

```sh
scouterctl capture --object-hash 123456 --date 20261107 \
  --from 2026-11-07T09:00:00Z --to 2026-11-07T09:10:00Z --timezone UTC \
  --include-profiles --directory captures/incident-001 --resume --no-input
```

Replace the example query with the values used to create your capture and use the same password input method. The CLI reconnects and authenticates for each invocation. It does not automatically retry or wait for the Collector to restart.

## After an interruption

1. Restore access to the same Collector and its data directory. Check the connection with `doctor`.
2. Rerun the capture command with `--resume`. Do not delete or modify the manifest or referenced page files.
3. Check the exit code and completion fields. A v3 capture can finish reading XLogs while profiles remain unavailable; another resume retries those profiles.

A closed transport can report `IO_ERROR` (exit 10), whereas a refused connection or timeout reports `CONNECTION_FAILED` (exit 5). Exit 10 can also mean a local file or integrity failure: preserve the capture and inspect the error before retrying. A damaged referenced page must be rejected, not silently repaired. In profile captures, `lastFailure` records a handled capture failure and clears after successful resume.

Pages that were already committed are validated before resume. An incomplete page is fetched again, including profiles already read but not committed. Temporary files not referenced by the manifest are not evidence. Repeating resume on a complete capture preserves its data.

## Recovery limits

Recovery preserves the records available in the fixed query window and the committed capture. It cannot recover telemetry that an agent or Collector lost during an outage, expired retention, deleted databases, or sampled requests. `executionComplete` does not certify complete upstream traffic; `upstreamCoverage` and `sampling` remain `unknown`.

## Supported formats and testing

The current CLI resumes XLog-only v1 and profile v3 captures. Existing v2 captures remain readable but cannot be resumed; use the creating CLI version or start a new v3 capture. See [capture compatibility](capture-compatibility.md).

Contributors can reproduce actual Collector stop/restart with the [restart recovery task](testing.md#collector-restart-recovery).
