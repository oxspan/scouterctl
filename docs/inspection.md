# Collector, Agent and current counters

Select a [connection and password input method](connections.md) before running these commands. Use `--interactive` for terminal input or `--no-input` with `SCOUTERCTL_PASSWORD` in scripts.

## Collector status and settings

```sh
scouterctl collector status
scouterctl collector config
scouterctl collector config --key net_tcp_listen_port
```

Status reports `usedHeapBytes`, `committedHeapBytes` and `serverTimeEpochMs`. Committed heap is not the JVM maximum heap. Collector metadata accompanies both results.

`doctor` and status also report the [Collector's JVM time-zone ID and client-rule offset](collector-timezone.md), including their provenance and reference time. This is separate from storage-date verification.

Configuration lists runtime settings exposed by the Collector alongside their defaults. Values are strings. The CLI shows values only for selected port, HTTP-enable and retention settings; other entries have `value: null`, `defaultValue: null` and `redacted: true`. Filtering by `--key` does not reveal hidden values. A missing setting exits 7. This is not a raw configuration-file export, and it does not include arbitrary custom properties.

These commands do not write settings or require a local Agent. They authenticate with the selected Collector account. Successful access does not prove that the Collector enforces distinct permissions for every operation; `effectivePermission` remains `unknown`.

## Agent status and settings

Choose a Java Agent's `objHash` from `object list`:

```bash
scouterctl agent status --object-hash 123456
scouterctl agent config --object-hash 123456
scouterctl agent config --object-hash 123456 --key net_collector_tcp_port
```

Both commands go through the selected Collector. Status includes the Agent name, type, version and Collector-reported `alive` flag. When available, `jvm` contains Java/VM versions, vendor, OS name and architecture; arbitrary system properties and personal paths are excluded. `jvmAvailability` is `available`, `offline` when the Collector reports the Agent as down, or `unavailable` when no JVM data is returned. An unavailable JVM is not proof of a healthy application. A transport timeout returns exit 5 instead of a status result.

Configuration lists the Agent's runtime settings and defaults. Visible settings include selected numeric and boolean controls, such as `trace_sql_parameter_max_length`, `profile_http_parameter_enabled` and `profile_http_querystring_enabled`, plus the text settings `net_collector_ip`, `obj_name` and `obj_type`. Other settings report null for current and default values with `redacted: true`, including when selected by `--key`. Neither command writes configuration. An unknown target, an offline Agent's configuration, unavailable configuration or a missing setting returns exit 7. Malformed responses return exit 6. The CLI remains on JDK 21 even when the Agent/application runs on Java 8.

## Active requests and thread dumps

Use one nonzero Java Agent hash from `object list`:

```sh
scouterctl agent active-requests --object-hash 123456
scouterctl agent active-requests --object-hash 123456 --include-text
scouterctl agent thread-dump --object-hash 123456
```

`active-requests` takes one live snapshot. It returns `collector`, `agent`, `requests`, `complete` and `textIncluded`. Each request has a decimal-string `txid` and `threadId`, the Agent's thread `state`, `elapsedMs` and decimal-string `threadCpuTimeMs`. CPU time is the thread's cumulative CPU time in milliseconds, not CPU used by that request; some Agents return zero when measurement is unavailable. A reported -1 is represented as null. Deferred or reactive requests may have thread ID `"0"` and state `"n/a"`. Rows are ordered by elapsed time descending. Only requests currently represented by the Agent are included; this is not a historical search or a count of all application activity.

Service and thread names are null unless `--include-text` is supplied. Even with that option, SQL statements, client IP addresses, login names and arbitrary request descriptions are excluded. The decimal `txid` can be used with `profile get` after the request finishes and reaches Collector storage. A complete, empty snapshot succeeds; a snapshot explicitly reported incomplete returns the rows with `executionComplete: false` and exit 8.

`thread-dump` explicitly requests raw diagnostic text and returns it in `data.lines`, with Collector and Agent identity. Thread names, stack traces, lock details and other JVM-produced text can contain sensitive data; review the output before sharing. No remote dump file or thread-control command is requested. The Agent/JVM may require attachment support; Java 9+ attachment-based Agents can require `-Djdk.attach.allowAttachSelf=true` at application startup. The CLI does not change that setting. Agent-side failures return a generic diagnostic without echoing raw attachment errors or local paths.

Dump `completeness` is `"unknown"`: the upstream protocol does not provide a reliable truncation indicator and some implementations cap output at 100,000 lines. Command `executionComplete` means the returned response was processed, not that every thread or frame was captured. The CLI rejects over 100,000 lines or 8,388,608 decoded characters, and active snapshots over 10,000 rows. These are post-decode checks, not bounds on Agent work or total transport allocation. A timeout does not guarantee upstream cancellation.

Unknown/offline targets, missing diagnostic replies and unavailable JVM dumps exit 7 (`NOT_FOUND`); malformed responses exit 6 (`PROTOCOL_ERROR`). The protocol cannot reliably distinguish an unsupported diagnostic from an Agent that disconnected after discovery. The commands use the existing Collector account, whose `effectivePermission` remains `unknown`; successful authentication does not establish per-operation authorization. Platform, JVM and Agent combinations need separate validation from the general Collector version list.

## Real-time counters

Use a target hash from `object list`:

```bash
scouterctl counter list --object-hash 123456
scouterctl counter get --object-hash 123456 --counter HeapUsed
scouterctl counter get --object-hash 123456 --counter TPS
```

Both commands return `data.agent` and a sorted `data.counters` array; `get` selects one exact name. Each entry has `name`, `displayName`, `unit`, `value` and `availability`. Numeric values are decimal strings, preserving integer precision. Units and display names come from the Collector's default/custom definitions for that object type; an unknown definition has null metadata. Values retain the Collector's units without conversion.

`available` includes genuine zero values. `unavailable` has `value: null`: the counter is defined but has no current cached value, or the Collector reports the Agent as offline. `unsupported` also has null value and means a cached value is not a supported numeric type; arbitrary text is excluded. Unrecognized names and missing targets return exit 7. Malformed responses and nonfinite numbers return exit 6.

These are the latest values in the Collector cache. The response supplies no sample timestamp, and the counters need not share a measurement window. The CLI does not certify freshness or replace missing values with zero. See [Host and process counters](host-counters.md) for Host Agent setup and tested combinations. Some counters need another producer: for example, `ProcCpu` can remain unavailable without the Host Agent, and GC counters may need another collection cycle after startup. TPS, response time and error rate are Agent aggregates, so they need not match a client-side HTTP batch's count or error fraction. The commands do not write settings or query historical time series.
