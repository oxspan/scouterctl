# Collector time zone

English | [한국어](ko/collector-timezone.md)

`doctor` and `collector status` report a `timezone` object alongside the existing `timezoneDisplayName`. For `collector status`, it is inside `data.collector`:

```json
{
  "id": "Asia/Seoul",
  "utcOffsetSeconds": 32400,
  "referenceTimeEpochMs": 1793491200000,
  "idSource": "jvm-property",
  "offsetSource": "client-zone-rules"
}
```

The ID comes from the Collector's reported JVM `user.timezone` property through the existing authenticated `SERVER_ENV` request. Java short IDs, when recognized, are expanded using Java's short-ID mapping. The offset is calculated by the **CLI's time-zone rules** at the Collector's `SERVER_TIME` timestamp; it is not a remotely measured offset. `timezoneDisplayName` remains the localized login label and is not used to infer an ID. Other JVM environment values are not included in output.

If the property is missing or cannot be parsed, `timezone` is `null`. No CLI-host zone or display-name guess is substituted. Invalid protocol types fail as protocol errors without printing their values. A configured property can differ from a runtime default changed after startup, and Collector/CLI time-zone database versions can differ; read the provenance before treating the values as proof of runtime state.

## Query mismatch checks

Online `xlog search`, `capture`, `counter history` and `counter export` compare `--timezone` offsets against the reported Collector zone using client rules over the requested half-open interval. Different IDs with equal offsets throughout the interval are accepted. The check uses the query's instants, including internal DST transitions, rather than comparing a historical query with today's offset.

A mismatch returns `INVALID_ARGUMENT` (exit 2), naming `--timezone` and the reported Collector zone, before emitting stream events or creating capture files. There is no override option. With unavailable zone metadata, the previous unverified query behavior remains. Transport and malformed-response errors still fail normally; they are not silently treated as missing metadata.

For a Collector reporting `Asia/Seoul`, this declared date/zone combination matches:

```sh
scouterctl xlog search --object-hash 42 --date 20261109 --timezone Asia/Seoul \
  --from 2026-11-09T13:00:00+09:00 --to 2026-11-09T13:01:00+09:00 --no-input
```

The same instants declared as `America/Los_Angeles` with date `20261108` are rejected. A UTC timestamp suffix is still valid input with `--timezone Asia/Seoul`; the timestamp syntax itself is not required to use the Collector's offset.

## Storage dates remain unverified

This is a comparison with a **reported JVM zone**, not confirmation of the storage partition. XLog `storageDateVerification: "unverified"` and capture `timezoneVerification: "operator-declared"` remain unchanged. Capture formats and strict-reader compatibility are unchanged; the new diagnostic fields are live output only.

Scouter's `DateTimeHelper` in 2.8.1 and 2.21.3 builds a calendar from local `20000101` with fixed 86,400,000-ms days. That calendar can differ from civil time around DST. For example, in `America/Chicago`, `2026-07-01T05:30:00Z` is July 1 by current zone rules but June 30 in that storage calendar. A matching zone check cannot certify the correct partition or prove that an empty result means no traffic. Confirm storage dates with the Collector operator when results are unexpected. This change does not repair that upstream calendar behavior or verify the server's cached calendar through the protocol.
