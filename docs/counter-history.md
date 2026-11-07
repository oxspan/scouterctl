# Counter history

Read one object's stored counter samples as JSONL. Use the Collector's storage date and declare its IANA timezone explicitly.

```sh
scouterctl counter history \
  --object-hash 123 --counter HeapUsed \
  --date 20261107 \
  --from 2026-11-07T09:00:00+09:00 \
  --to 2026-11-07T10:00:00+09:00 \
  --timezone Asia/Seoul \
  --no-input > heap.jsonl
```

Supply connection options or a saved context and `SCOUTERCTL_PASSWORD` as described in [installation](installation.md). Use `counter list --object-hash 123` to discover currently defined names. A historical object need not be online; its identity comes from the requested day's object records.

The interval is **[from, to)** in epoch milliseconds. Both endpoints must fit within one Collector-local storage day (the exclusive end may be the next midnight). The timezone is operator-declared; the Collector login's display name does not establish an IANA timezone.

## Output

The first event uses `schemaVersion: "scouterctl.counter-stream/v1"` and includes `source`, `query`, `target`, `counter` (`name`, `displayName`, `unit`) and:

- `resolution: "stored-realtime"`: stored samples, without resampling or aggregation.
- `intervalMs: null`: no fixed sampling interval is asserted.
- `missingValues: "omitted"`: the Collector omits absent samples; the CLI never fills gaps with zero.
- `metadataScope: "current-collector-definition"`: labels and units describe the current Collector definition, not a versioned historical definition. Unknown units are `null`.

Each record carries `data.time` (epoch milliseconds), `data.value` (a numeric decimal string or `null`) and `data.availability`. Numeric zero remains `"0"` or its stored floating-point representation. Non-numeric values become `null` / `"unsupported"`; their original text is not exposed. Non-finite numbers are rejected. Sample timestamps and order are preserved, including repeated timestamps.

The final event has `type: "complete"`, `executionComplete: true`, `records`, `upstreamCoverage: "unknown"` and `sampling: "unknown"`. Require this event **and exit 0** before accepting the export as complete. The shared [error format and exit codes](cli-contract.md) apply.

## Limits

This is a bounded, single-object, single-counter, single-day query. The whole reply is decoded and buffered before JSONL output. Replies with more than 100,000 samples are rejected after decoding; this does not bound decoding memory or Collector-side work. Existing blob limits are not a total-reply size limit. Oversized or malformed replies fail without a successful completion event. There is no pagination, retry, checkpoint or resume for this command. Use [counter export](counter-export.md) for multi-day files and checkpointed resume.

A missing daily object returns `NOT_FOUND`. An empty interval or an unrecorded counter produces a complete stream with zero records. That does not prove that the counter existed, traffic was absent, retention was complete, or collection was lossless. Current metadata can differ from metadata at collection time.

Live checks cover Collector 2.8.1 and 2.21.3 on Java 8, queried by CLI Java 21 on Linux and Windows x64. See [supported versions](supported-versions.md) for the broader project's verification boundaries.
