# Resumable counter export

`counter export` saves a counter's stored samples over multiple days. It splits the interval into smaller, non-overlapping windows and checkpoints each completed file. Use [counter history](counter-history.md) for a single-day JSONL stream on stdout.

```sh
mkdir captures
scouterctl counter export \
  --object-hash 123 --counter HeapUsed \
  --from 2026-11-01T00:00:00+09:00 \
  --to 2026-11-04T00:00:00+09:00 \
  --timezone Asia/Seoul --chunk-minutes 60 \
  --directory captures/heap-export --max-chunks 24 --no-input

# Same source, interval, timezone, object, counter and chunk size:
scouterctl counter export \
  --object-hash 123 --counter HeapUsed \
  --from 2026-11-01T00:00:00+09:00 \
  --to 2026-11-04T00:00:00+09:00 \
  --timezone Asia/Seoul --chunk-minutes 60 \
  --directory captures/heap-export --max-chunks 100 --resume --no-input
```

Create the parent `captures` directory if it does not exist. Supply connection options or a saved context and `SCOUTERCTL_PASSWORD`. The first invocation requires a new directory. Resume requires an existing valid checkpoint and a connection to the same Collector. Use the same query and chunk size; only the per-invocation `--max-chunks` budget may change.

## Files and completion

- `manifest.json` uses `scouterctl.counter-capture/v1`, independent of XLog capture formats. It records the source, query, ordered chunks, each chunk's time window, dated target, counter definition, status, filename, SHA-256 and record count.
- `chunk-000001.jsonl`, etc. contain `CounterPoint` JSON objects: `time` (epoch milliseconds), `value` (numeric string or `null`) and `availability`. Read them in manifest order and use each chunk's metadata for units and identity. Sample order and repeated timestamps are preserved; no deduplication, resampling or zero filling is applied.
- A chunk with `status: "object_unavailable"` has no dated object record, null target/definition and an empty file. An `"available"` chunk can also have zero samples. These states are distinct and neither proves that telemetry was never produced.
- Stdout contains one normal [result envelope](cli-contract.md) whose `data` is the manifest. Budget exhaustion returns `executionComplete: false` and exit 8; repeat with `--resume`. A final `executionComplete: true` and exit 0 mean all requested windows were processed, not that collection was lossless. Coverage and sampling remain `unknown`.

The CLI writes a chunk first, then atomically replaces the manifest. On resume it validates the contiguous window prefix, filenames, hashes, record counts and sample fields before fetching more data. A failed or interrupted window is retried from its beginning; an uncommitted file for that next window is replaced. Committed files remain unchanged. Repeating a completed resume validates and returns the existing manifest without rewriting it.

Connection or I/O errors emit the normal error envelope and a nonzero exit. Previously checkpointed chunks remain available. Changed query/source conditions are rejected. Damaged files/checkpoints fail validation without rewriting committed data. A directory lock prevents concurrent cooperating writers; the hidden `.lock` file may remain after the process exits and does not mean a writer is still running.

## Time windows and limits

`--date` is not used: storage dates are derived from the operator-declared Collector IANA timezone. Windows are [from, to), capped by the configured duration and the next local midnight, including 23/25-hour DST days.

Defaults are 60 minutes per chunk and 24 chunks per invocation. Chunk size accepts 1..1440 minutes and the invocation budget 1..10000 chunks. A query is limited to 366 elapsed days and 10000 planned chunks. Each decoded response may contain at most 100000 samples; each saved chunk and manifest is limited to 16 MiB. If a chunk is too large, start a new export with a smaller chunk size; the existing query cannot be changed on resume.

Collector replies are decoded and buffered before a file is written. Sample/file limits do not bound pre-decode memory or Collector-side work. Atomic replacement and locks address process interruption and cooperating writers, not arbitrary external edits or power-loss durability. JSONL files and manifests use UTF-8; keep all referenced files together.

Counter units/labels come from the current Collector definition at each query, not a versioned historical definition. A resumed export may therefore contain different per-chunk definitions. Completed chunks are not refreshed for late-arriving data; this is a checkpointed extraction, not a Collector-wide snapshot or a live tail. Historical object absence and sampling/retention remain visible limitations.


See [supported versions](supported-versions.md) and [Testing](testing.md) for the validation scope and reproducible integration tasks.
