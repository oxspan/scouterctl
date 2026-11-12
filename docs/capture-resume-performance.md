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

The CLI still allows `--max-pages` 1..40 and profile page sizes up to 100, with the existing capture limit of 10,000 pages. A bounded single-process continuation could retain IDs between batches and avoid repeated prefix validation, but it would need an explicit total-work budget, cancellation/checkpoint behavior and rules for on-disk changes between batches. Changing the current per-invocation budget contract is outside this fix. Persisting an unchecked ID cache or skipping validation on later invocations would weaken integrity guarantees and is not a substitute.
