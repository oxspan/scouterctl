# Capture recovery

Capture commits one complete page at a time. If the connection fails while reading the next XLog page or its profiles, the manifest retains the last committed cursor. Resume the same directory with the original endpoint, object, date, time range, page size, and profile/text options:

```sh
java -jar scouterctl-VERSION-all.jar capture <original options> --resume
```

Use the concrete original command in place of `<original options>`; keep the same password input method. The CLI reconnects and authenticates for each invocation. It does not automatically retry or wait for the Collector to restart.

## After an interruption

1. Restore access to the same Collector and its data directory. Check the connection with `doctor`.
2. Rerun the capture command with `--resume`. Do not delete or modify the manifest or referenced page files.
3. Check the exit code and completion fields. A v2 capture can finish reading XLogs while profiles remain unavailable; another resume retries those profiles.

A closed transport can report `IO_ERROR` (exit 10), whereas a refused connection or timeout reports `CONNECTION_FAILED` (exit 5). Exit 10 can also mean a local file or integrity failure: preserve the capture and inspect the error before retrying. A damaged referenced page must be rejected, not silently repaired. In v2, `lastFailure` records a handled capture failure and clears after successful resume.

Pages that were already committed are validated before resume. An incomplete page is fetched again, including profiles already read but not committed. Temporary files not referenced by the manifest are not evidence. Repeating resume on a complete capture preserves its data.

## Scope of the guarantee

Recovery preserves the records available in the fixed query window and the committed capture. It cannot recover telemetry that an agent or Collector lost during an outage, expired retention, deleted databases, or sampled requests. `executionComplete` does not certify complete upstream traffic; `upstreamCoverage` and `sampling` remain `unknown`.

## Reproduce the real-service check

Build the supported Scouter Collector and Java agent first. With JDK 21 and a compatible Collector JVM:

```sh
SCOUTER_COLLECTOR_JAVA=/path/to/collector/java \
  ./gradlew recoverySmoke -PscouterRoot=/path/to/scouter
```

Optionally pass `-PcliJar=/path/to/extracted/scouterctl-VERSION-all.jar` to verify an extracted package. The helper starts isolated, loopback-only Collector and instrumented Spring processes. A test-only TCP relay pauses an actual request after a committed page, then the helper stops and restarts the Collector against the same database. It checks v1 XLog-page interruption and v2 interruption partway through profile retrieval, exact recovered records/profiles, unchanged committed pages, and repeated-resume stability. Requests sent during the outage are outside the fixed query and are not asserted recoverable.

The task prints its temporary evidence directory, including `recovery-summary.json` and process teardown results. It stops all started services and deletes the temporary account file; retain the evidence needed for review and remove the directory afterward. This real Collector/Servlet check runs separately from the portable Kotest suite. CI uses protocol fixtures on Linux, Windows, and macOS; those checks do not establish real Collector instrumentation on each platform.
