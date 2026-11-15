# Profile capture resume work

Profile capture (`capture --include-profiles`) validates every committed page before recovering profiles or appending a new page. It checks SHA-256, JSON/record structure, object/time scope, profile identity and transaction uniqueness. The resume path collects existing transaction IDs during this same traversal; it does not reread all pages solely to rebuild that set. A duplicate incoming transaction is still rejected before its profile is fetched or its page is committed.

Pages with unavailable profiles require an additional read for recovery and can be rewritten with new checksums. Already execution-complete captures still validate their committed pages and retry unavailable profiles before returning. Unreferenced or partial files are not committed pages. Capture formats, error codes and CLI budgets are unchanged.

## Reproduce the measurement

With JDK 21, run the opt-in application-level measurement:

```sh
./gradlew --no-daemon resumeReadCheck
```

Use `gradlew.bat` on Windows. Results require a new directory; `-PresumeReadResults=PATH` selects one. Preserve an earlier result directory before choosing a new path for another trial. This task is separate from the default tests and from offline `inspect compare` scale validation.

To compare an earlier implementation, preserve its fat JAR and run the current measurement harness with that JAR first on the runtime classpath:

```sh
./gradlew --no-daemon resumeReadCheck -PcliJar=PATH/TO/BASELINE-all.jar -PresumeReadResults=build/resume-read-before
```

Use separate fresh JVM runs for each trial and compare the recorded implementation class hashes. Supplying a baseline JAR changes the measured application/store implementation; it does not replace the current synthetic fixture or measurement code.

The fixture uses the real file-backed `EvidenceStore` and a synthetic observation source, without a Collector, credentials or simulated network delay. It captures 120 pages in three successive 40-page application invocations, then resumes the execution-complete capture once. Both 2 and 100 records per page are measured, with 20 small structural SQL steps per profile. Previously committed profiles must not be fetched again. This is local application/filesystem work, not production Collector throughput or packaged CLI startup time.

`report.json` records OS, architecture, JDK, CPU count, heap limit, implementation class hashes and each invocation's existing page/record count, page bytes, read bytes, full-file scan-count histogram, elapsed capture time, preparation time to the first new XLog page request, added pages and profile requests. A completed capture makes no new page request, so its preparation time is null. `reads-*.jfr` retains the raw [JDK Flight Recorder](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jfr/jdk/jfr/Recording.html) events.

JFR `jdk.FileRead` events use a zero duration threshold and are filtered to each fixture's page paths. The measurement sums positive read bytes per page and divides by that page's byte size; it does not count low-level read calls as whole scans. Capture timing excludes recorder start/dump/stop and report processing, but includes manifest I/O, page validation, synthetic profile creation, page writes and checkpointing. Recorder overhead within the measured operation remains. Preparation ends at the first synthetic page request and includes local validation/recovery. OS cache, JIT, GC and host contention are not controlled; timings are observations, not test thresholds or a universal speedup claim. No maximum-size memory or production crossover claim follows from these fixtures.

## Remaining bounds and tradeoffs

For an ordinary incomplete capture with no unavailable profiles, the existing-page scan counts are 0, 40 and 80 over the three capture invocations, rather than 0, 80 and 160 before the change. The subsequent completed resume scans all 120 pages once in both versions. Extra reads for unavailable-profile recovery are outside this ordinary-case count.

Combining the passes halves these prefix scans; it does not eliminate their cumulative growth. With B new pages per invocation and P = mB total pages, ordinary repeated resumes scan Bm(m-1)/2 = (P²/B - P)/2 existing pages. Each individual resume remains linear in the committed prefix. Transaction-ID membership still requires memory proportional to committed records. Collecting through the existing validated callback retains the application's ID set while the store's uniqueness set is live, increasing simultaneous set storage; peak heap was not measured here.

The default invocation still allows `--max-pages` 1..40 and profile page sizes up to 100, with the existing capture limit of 10,000 pages. Repeated separate invocations retain the cumulative cost above.

## Bounded single-process continuation

Profile captures can opt into `--max-total-pages N` to continue beyond the ordinary `--max-pages` budget in the same process. N must be between `--max-pages` and 10,000 and counts **new pages in this invocation**, including after `--resume`; it is not the final capture size. Defaults and behavior without this option are unchanged. This option requires `capture --include-profiles`.

```sh
scouterctl capture --include-profiles --resume --directory captures/incident \
  --date 20261101 --from 2026-11-01T00:00:00Z --to 2026-11-01T01:00:00Z \
  --timezone UTC --object-hash 42 --max-total-pages 1000 --no-input
```

The process validates the existing prefix once, retains transaction IDs in memory and checkpoints each new page before requesting the next. Reaching the budget without finishing returns the ordinary incomplete capture result (exit 8). Resume still fully validates all committed pages; no persistent unchecked ID cache or format change is introduced. Profile recovery can reread pages whose profiles were unavailable.

Profile writers hold an exclusive `.writer.lock` for the invocation: a file lock on Unix and an exclusive file handle on Windows, including Windows access to WSL UNC paths where byte-range locking is unavailable. Concurrent writers using the same platform fail with `IO_ERROR`; readers remain available. Do not mix Windows and Linux writers on a WSL-shared directory: these platform mechanisms do not establish cross-platform coordination. A changed or removed manifest is detected before checkpoint replacement and is not overwritten. Atomic moves and advisory locks do not defend against an external process editing immutable page files, replacing lock files, or racing the final compare-and-replace. Do not modify a live capture directory. Stop the writer and use a fresh full validation/resume after external edits. This restriction also applies between ordinary page-budget boundaries inside a continued invocation.

Cancellation is checked before each page and profile request and before committing a new page. A cooperative cancellation reports `PARTIAL_RESULT` with the last committed cursor intact; retry with `--resume`. On terminal termination the CLI allows up to five seconds for in-flight work to notice cancellation and release its writer lock. An uninterruptible network call or forced termination can outlive that grace period; already committed pages remain recoverable and unreferenced partial/page files are ignored. Cancellation does not guarantee upstream cancellation.

ID storage is still O(committed records). Continuation removes repeated prefix scans between separately launched invocations, but it does not bound record memory independently of capture size. Choose a finite total budget for the available heap and workload.

Run `./gradlew captureContinuationCheck -PcontinuationResults=NEW_DIRECTORY` for a file-backed comparison of repeated 40-page resumes and one continued invocation after a common 40-page seed. It measures 120 and 400 pages with 100 records and 20 structural SQL steps per profile, under a 512 MiB heap. The JSON report includes positive existing-page read bytes from JFR, measured capture elapsed time, per-heap-pool peak usage and post-operation heap usage. Pool peaks can occur at different instants, include measurement overhead and are not simultaneous peak heap or process RSS. GC timing and shared-JVM warmup affect memory and time observations; no universal speedup or memory reduction follows from these synthetic local runs.
