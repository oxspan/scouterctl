# Capture scale validation

For synthetic profile-capture resume reads and preparation timing, see [profile capture resume work](capture-resume-performance.md). The workflow below measures offline comparison, a separate workload.

Run the opt-in **Capture scale validation** GitHub Actions workflow on a branch to measure offline `inspect compare`. It builds one CLI JAR and one synthetic capture archive, then verifies and uses the same bytes on Linux, Windows and macOS. It does not publish a release or run on every pull request. The macOS measurement job is outside the supported release-platform scope. You can also enable `scale_validation` when manually running **CI and release**, including on a feature branch.

For a local Linux, macOS or Windows checkout with JDK 21:

```sh
./gradlew --no-daemon scaleFixtures
./gradlew --no-daemon scaleCheck
```

Use `gradlew.bat` on Windows. The generator requires a new `build/scale-inputs` directory; measurements require a new `build/scale-results` directory. Preserve results elsewhere before removing either directory for a repeat run. `-PscaleInputs=PATH` selects an existing input directory and `-PcliJar=PATH` selects a built JAR for measurement. Linux and macOS require `/usr/bin/time`; Windows uses its built-in Windows PowerShell and Win32 process memory API.

## Workloads and checks

Each capture stores 100 records per page, one SQL profile step per record, resolved synthetic service names, and no bind values. All fixtures are generated locally without a Collector or credentials.

| Case | Records per capture | Services per capture | Expected result |
| --- | ---: | ---: | --- |
| Increasing records | 1,000 / 10,000 / 100,000 | 100 | Full comparison, checked aggregates and deltas |
| Accepted boundary | 10,000 | 1,000 | All 1,000 services returned |
| Disjoint service sets | 10,000 | 1,000 each | Union 2,000, output 1,000, truncation explicit, no invented deltas |
| Rejected boundary | 1,001 | 1,001 on either side | Exit 8, `PARTIAL_RESULT`, no data |
| Damaged final page | 100,000 | 100 | Exit 10, `IO_ERROR`, no data even with output limit 1 |

Successful cases run three times in fresh JVMs. Rejected cases run once. Expected totals come from the fixture's fixed 10% / 30% error rates and 100 ms / 150 ms request durations, independently of the production aggregation code. Every input file is fingerprinted before and after execution. A wrong exit code, aggregate, delta, fingerprint, missing measurement or process deadline fails the workflow. Performance values are observations, not pass/fail thresholds.

## Measurement scope

- Each CLI starts with `-Xms32m -Xmx512m`. Wall time includes JVM startup, page validation, aggregation, serialization and output capture. Fixture generation, Gradle, and Windows PowerShell initialization are excluded. Unix measurements include the small `/usr/bin/time` wrapper startup cost.
- Linux reports GNU `time` maximum resident set size (KiB converted to bytes). macOS reports BSD `time` maximum RSS in bytes. Windows reports [Win32 `PeakWorkingSetSize`](https://learn.microsoft.com/en-us/windows/win32/api/psapi/ns-psapi-process_memory_counters) in bytes from a retained process handle. These are OS process memory metrics, not Java heap usage, and their accounting differs across operating systems.
- A CLI execution has a 120-second Windows / 130-second outer safety deadline. A hung child is terminated. This is a harness limit, not a product timeout or latency guarantee.
- Three sequential fresh JVM runs do not control disk cache, host contention, CPU architecture, antivirus, or JIT effects. Compare results within their recorded host context; do not rank operating systems by these numbers.
- Coverage stops at 100,000 records per input with a small SQL profile. It does not establish capacity at the capture format's maximum page count, huge individual profiles, concurrent writers, online capture throughput, or production service latency. Input files must remain immutable during comparison.

The `scale-results-*` artifacts retain CLI output, memory measurements and `report.json` for 14 days. The report records runtime/OS/architecture, processor count, heap settings, JAR and input-fingerprint SHA-256, paired input bytes, and individual execution measurements. Input archive checksums are also printed in job logs.
