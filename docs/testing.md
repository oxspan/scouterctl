# Testing

Run the ordinary Kotlin/Kotest suite with JDK 21. Linux also requires Bash, `jq`, `sha256sum` and `unzip` on PATH:

```sh
./gradlew test
./gradlew test --tests 'dev.scouterctl.CliOutputContractTest'
```

Use `gradlew.bat` on Windows. The suite uses local protocol/file/process fixtures and requires no Collector account. [Local CI](local-ci.md) runs the workflow build job with act. Choose focused tests while developing; run the complete checks when the change warrants them.

The following integration tasks start isolated Collector/Agent/application processes. Use test installations and data, never production directories. Gradle still runs on JDK 21 even when a fixture uses Java 8. Each task prints its evidence directory, stops its child processes and removes its temporary Collector account. Preserve useful output and remove the temporary directory when finished.

## Coverage

Run the complete suite, generate JaCoCo HTML/XML reports, and enforce minimum coverage:

```sh
./gradlew -Pcoverage=true coverageCheck
```

The initial minimums are 90% for lines and 80% for branches. Coverage includes all production classes from the main source set, combining the test JVM with packaged CLI subprocesses (including the extracted command launcher). Dependencies, test fixtures, optional real-Collector smoke checks, and scale measurements are outside this scope. Ordinary `test` runs leave coverage disabled; use the full suite without `--tests` for a comparable baseline. Coverage requires the locally built CLI, so do not combine it with `-PcliJar`.

Each coverage run executes fresh tests, clears old execution data/reports, and writes a separate execution file for each CLI process. JVM arguments enable the agent without startup messages on stdout/stderr. Missing test JVM or subprocess data, empty report counters, or coverage below either minimum fails verification.

Reports are written to `build/reports/jacoco/test/html/index.html` and `build/reports/jacoco/test/jacocoTestReport.xml`; `build/reports/jacoco/summary.md` contains counts, percentages, thresholds, and pass/fail results. Linux CI runs coverage and package verification in one Gradle invocation, publishes the summary, and retains reports for 14 days even when a threshold fails. Local act runs save the same reports as artifacts. Historical release-asset repair does not apply new coverage checks to tagged code.

No Codecov credentials are needed. PR coverage comparisons and configuring a required merge check are separate follow-up work.

## Collector and Agent checks

Use an isolated Collector installation and data directory. The optional smoke tools create their own configuration and accounts and stop the processes they start. Never point them at production data.

The normal Kotest suite uses protocol fixtures; it does not download Collector distributions or run these integrations automatically.

On Linux, select a Collector from an extracted official release archive:

```bash
export SCOUTER_COLLECTOR_JAVA="$JAVA8/bin/java"
export SCOUTER_COLLECTOR_CLASSPATH="$SCOUTER_DIST/server/*:$SCOUTER_DIST/server/lib/*"
```

For matching legacy Agent/Collector checks, compile the existing Kotlin HTTP fixture for Java 8 and use its separate runtime classpath:

```bash
export SCOUTER_AGENT_JAR="$SCOUTER_DIST/agent.java/scouter.agent.jar"
export SCOUTER_SERVICE_JAVA="$JAVA8/bin/java"
export SCOUTER_SERVICE_CLASSPATH="$(./gradlew -q legacyFixtureClasspath)"
./gradlew agentSmoke -PscouterRoot="$SCOUTER_SOURCE"
# Run only Agent status/settings checks:
./gradlew agentInspectionSmoke -PscouterRoot="$SCOUTER_SOURCE"
# Counter reads, optionally with real HTTP load:
SCOUTER_COUNTER_LOAD=true ./gradlew counterSmoke -PscouterRoot="$SCOUTER_SOURCE"
# Stored counter history, units, boundaries and checkpointed multi-day export:
./gradlew counterHistorySmoke
# Add actual Collector stop/restart and damaged-file checks on representative releases:
SCOUTER_COUNTER_RECOVERY=true ./gradlew counterHistorySmoke
```

`JAVA8`, `SCOUTER_DIST` and `SCOUTER_SOURCE` refer to your Java 8 installation, extracted distribution and built Scouter source checkout. The legacy JDBC dependency is test-only and is excluded from the CLI package. Build/run Gradle with JDK 21.

For Servlet checks, unset the legacy `SCOUTER_AGENT_JAR`, `SCOUTER_SERVICE_JAVA` and `SCOUTER_SERVICE_CLASSPATH` overrides. Use `springSmoke` with a built Java 21-capable Agent from the source checkout. `recoverySmoke` adds actual Collector stop/restart to that scenario. `collectorInspectionSmoke` starts only the Collector and checks status, settings, authentication and unchanged configuration/account files; it does not start an Agent or application. The optional `SCOUTER_SERVICE_*` and `SCOUTER_AGENT_JAR` overrides apply to `agentSmoke`, `agentInspectionSmoke`, `counterSmoke` and `counterHistorySmoke`, not `springSmoke`.

See [capture recovery](capture-recovery.md) and [protocol library provenance](../vendor/README.md).

`counterHistorySmoke` uses the configured `SCOUTER_COLLECTOR_*`, `SCOUTER_AGENT_JAR` and `SCOUTER_SERVICE_*` values; with all overrides set, it does not require a Scouter source checkout. It prints an isolated evidence directory containing the raw CLI output, reference TSV, export files, summary and teardown state. Fixture processes stop and the temporary account file is removed in `finally`. The test never targets an existing running Collector or its data.

Run the external Collector smoke separately from the protocol unit suite: both allocate temporary local ports, and launching both concurrently can cause a fixture bind collision. The optional recovery mode waits for the Collector's periodic index flush, then requires the restarted independent reference to match; immediate readability is not a disk-durability guarantee. No Collector fixture is downloaded or launched by ordinary `test` or hosted CI.

## Host counters

Use isolated configurations and data. Select extracted official distributions and the Java 8 Kotlin fixture from [Collector and Agent checks](#collector-and-agent-checks). Additionally set:

```bash
export SCOUTER_HOST_JAVA="$JAVA8/bin/java"
export SCOUTER_HOST_JAR="$HOST_DIST/agent.host/scouter.host.jar"
# Optional real reception over a non-loopback interface:
export SCOUTER_HOST_NETWORK_URL="https://api.github.com/repos/scouter-project/scouter/releases/tags/v2.21.3"
./gradlew hostCounterSmoke -PscouterRoot="$SCOUTER_SOURCE"
```

`HOST_DIST` is the extracted distribution supplying the Host Agent. The smoke starts its own Collector, Host Agent and Java service, uses a private registry, drives real SQL HTTP requests, writes and syncs a 64 MiB temporary file, and stops its child processes. An optional network response is discarded and bounded to 64 MiB. Host network collection excludes loopback traffic; local HTTP requests alone do not prove a nonzero network rate.

The default smoke requires disk/network values. For the known 2.21.3 missing-field case, `SCOUTER_HOST_EXPECT_IO=false` instead verifies that those fields remain unavailable after the collection interval. That result must not be reported as successful I/O collection.

From WSL, optional native Windows client verification uses `SCOUTER_WINDOWS_JAVA` as a WSL executable path to Windows `java.exe`. Set `SCOUTER_WINDOWS_JAR` and `SCOUTER_WINDOWS_CONFIG` to native Windows paths for a local copy of the same CLI JAR and a nonexistent test config file. The helper passes credentials through the child environment and scopes `WSLENV` to that process.

## Collector restart recovery

Build the supported Scouter Collector and Java agent first. With JDK 21 and a compatible Collector JVM:

```sh
SCOUTER_COLLECTOR_JAVA=/path/to/collector/java \
  ./gradlew recoverySmoke -PscouterRoot=/path/to/scouter
```

Optionally pass `-PcliJar=/path/to/extracted/scouterctl-VERSION-all.jar` to verify an extracted package. The helper starts isolated, loopback-only Collector and instrumented Spring processes. A test-only TCP relay pauses an actual request after a committed page, then the helper stops and restarts the Collector against the same database. It checks v1 XLog-page interruption and v3 interruption partway through profile retrieval, exact recovered records/profiles, unchanged committed pages, and repeated-resume stability. Requests sent during the outage are outside the fixed query and are not asserted recoverable.

The task prints its temporary evidence directory, including `recovery-summary.json` and process teardown results. It stops all started services and deletes the temporary account file; retain the evidence needed for review and remove the directory afterward. This real Collector/Servlet check runs separately from the portable Kotest suite. Release checks run protocol fixtures on Linux and Windows; these do not establish real Collector instrumentation on either platform.

## Offline scale measurements

The optional [capture scale workflow](capture-scale-validation.md) uses synthetic captures and reports timing/memory measurements. Its macOS diagnostic job is separate from the supported release-platform checks.
