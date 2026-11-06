# Supported Collector versions

The current online commands accept these tested releases. This is an explicit list, not a promise that every intermediate or future release is compatible.

| Collector releases | Matching Agent and Java 8 HTTP/JDBC service | Agent 2.21.3 and Spring Boot 3 on Java 21 |
| --- | --- | --- |
| 2.0.0, 2.0.1 | Passed | Not supported by this Agent/Collector combination |
| 2.5.0, 2.5.1 | Passed | Not supported by this Agent/Collector combination |
| 2.6.0, 2.6.1, 2.6.2 | Passed | Not supported by this Agent/Collector combination |
| 2.7.0, 2.7.1 | Passed | Not supported by this Agent/Collector combination |
| 2.8.1 | Passed | Passed |
| 2.10.0, 2.10.2 | Passed | Passed |
| 2.15.0, 2.17.1, 2.20.0 | Passed | Passed |
| 2.21.1, 2.21.2, 2.21.3 | Passed | Passed |

Collector processes in these checks ran on Temurin Java 8 (`1.8.0_502`) on Linux. The CLI ran separately on JDK 21. A Collector does not need the CLI's Java version.

The Java 8 service used explicit service/method hooks and real HTTP and JDBC calls; it was not a Servlet auto-instrumentation test. The Spring checks used Servlet auto-instrumentation. The matching Agent came from each Collector's official release archive. No telemetry records were fabricated for either scenario.

## What was checked

For all 18 releases: login and authentication failure, application discovery, 36 HTTP-generated XLogs, service/error/duration checks, method and SQL profiles, half-open query boundaries, an empty query, and an eight-page transaction capture interrupted and resumed without missing or duplicate records. Fixture processes were stopped and temporary account files removed.

For the nine releases from the Spring column: 15 HTTP requests produced 21 Servlet XLogs/profiles, including error dispatches. Checks covered SQL text, application and SQL errors, profile captures, offline search/inspection/service summaries and comparison after the Collector stopped.

Before 2.8.1, the Collector does not handle the `XLOG_PROFILE2` telemetry emitted by Agent 2.21.3. A mixed-version test found XLogs but empty profiles. That is an Agent-to-Collector ingestion mismatch; changing the CLI cannot recover profiles that were never stored. Use a compatible Agent/Collector combination.

Collector status and runtime-settings inspection are validated separately with `collectorInspectionSmoke`. Agent status/JVM and runtime-settings reads are checked with matching Java 8 Agents using `agentInspectionSmoke` across the same 18 releases. Linux and native Windows JDK 21 clients are checked against 2.8.1 and 2.21.3; the Collector/Agent processes run on Linux. See [Quick start](../QUICKSTART.md#collector-status-and-settings) for output units and hidden values.

Real-time counter reads use `counterSmoke` with matching Agents on Java 8. The same 18 releases are checked for counter definitions/units, list/get, heap samples, missing counters/targets, authentication and unchanged configuration. The 2.8.1 and 2.21.3 checks also send real slow/SQL/error HTTP traffic and observe positive TPS, response time and error-rate aggregates. Native Windows and Linux clients are checked on those two releases. The JVM/OS scope remains the same as above.

## Limits

- Versions absent from the table, including snapshots and unverified patch releases, are rejected with `VERSION_MISMATCH` and exit 9.
- The CLI still uses the pinned 2.21.3 protocol library. That library version is distinct from the supported Collector versions.
- These results cover the current query and capture commands. Future configuration or Agent-control commands need their own version checks.
- This does not certify every Agent framework integration, Java distribution/update, Collector configuration or operating system. Additional Collector JVM versions have not been established by these checks.
- Collector 2.21.3 failed JAXB initialization in one JDK 21 setup; its successful Java 8 execution must not be described as Collector-on-JDK-21 validation.

## Reproduce the checks

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
```

`JAVA8`, `SCOUTER_DIST` and `SCOUTER_SOURCE` refer to your Java 8 installation, extracted distribution and built Scouter source checkout. The legacy JDBC dependency is test-only and is excluded from the CLI package. Build/run Gradle with JDK 21.

For Servlet checks on the compatible releases, use `springSmoke` with a built Java 21-capable Agent from the source checkout. `recoverySmoke` adds actual Collector stop/restart to that scenario. `collectorInspectionSmoke` starts only the Collector and checks status, settings, authentication and unchanged configuration/account files; it does not start an Agent or application. The optional `SCOUTER_SERVICE_*` and `SCOUTER_AGENT_JAR` overrides apply to `agentSmoke`, `agentInspectionSmoke` and `counterSmoke`, not `springSmoke`.

See [Capture recovery](capture-recovery.md), [Local CI](local-ci.md) and [protocol library provenance](../vendor/README.md).
