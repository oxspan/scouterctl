# Supported Collector versions

Online commands accept the releases below. Versions outside this list, including unlisted patch releases and snapshots, return `VERSION_MISMATCH` (exit 9).

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


## JVM and instrumentation scope

The CLI requires JDK 21. The Collector checks ran on Linux with Temurin Java 8 (`1.8.0_502`); a Collector does not need the CLI's Java version.

The matching-Agent column covers a Java 8 HTTP/JDBC application with explicit service and method hooks. The Spring column covers Agent 2.21.3 with Spring Boot 3 Servlet auto-instrumentation on Java 21. Before Collector 2.8.1, that Agent's `XLOG_PROFILE2` telemetry is not handled correctly: XLogs may arrive without profiles. Use a compatible Agent/Collector combination.

| Capability | Checked Collector releases |
| --- | --- |
| Login, discovery, XLogs, profiles and resumable capture | All releases above |
| Collector/Agent inspection and real-time counters | All releases above |
| Stored counter history and checkpointed multi-day export | All releases above |
| Full Collector restart recovery and native Windows client checks | 2.8.1 and 2.21.3 |
| Host/process CPU, memory and I/O | [Separate Host Agent combinations](host-counters.md) |

Windows client checks connect to Linux-hosted Collectors and Agents. They do not establish Windows-hosted Agent support. The CLI uses a pinned 2.21.3 protocol library; that library version is independent of the supported server list.

These results do not cover every Java distribution/update, Agent integration, Collector configuration or operating system. Collector 2.21.3 passed on Java 8 in these checks; running the CLI on JDK 21 does not establish Collector-on-JDK-21 support. Support for new commands or additional versions needs separate validation.

## Reproduce the checks

Contributors can run the isolated Collector/Agent and Spring checks in [Testing](testing.md#collector-and-agent-checks). Ordinary tests use local protocol fixtures and do not download or launch a Collector.
