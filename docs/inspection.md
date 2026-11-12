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
