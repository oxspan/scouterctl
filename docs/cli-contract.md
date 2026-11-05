# CLI output and compatibility contract

This contract describes the current CLI boundary for scripts. It is separate from the CLI release version and the on-disk [capture format](capture-compatibility.md). It does not turn this POC into a general Scouter protocol client: online access still supports Collector 2.21.3 only.

## Process and channels

- Normal command output is UTF-8 JSON on stdout, terminated by a newline. `xlog search` emits JSONL: parse each nonempty line as one JSON object.
- Command and argument errors also go to stdout as JSON. Do not assume stderr is the error API. Keep stdout and stderr separate when parsing.
- Explicit interactive password input uses a terminal and a human-readable prompt on stderr. Automation should provide `SCOUTERCTL_PASSWORD` and use `--no-input`; it must not depend on prompt text.
- `--help` and `--version` are plain-text stdout with exit 0, outside the JSON contract. Help layout and diagnostic messages may change. Parse error codes rather than message text.
- This contract applies after the JVM and CLI start successfully. A missing/incompatible JVM, process termination or an unwritable output stream can prevent a valid JSON response. Missing or malformed output must not be treated as successful execution.

## Single-result commands

`doctor`, `auth login`, `object list`, `profile get`, `capture`, `context add/list/use`, and the `inspect` family emit one result or one error object.

```json
{"schemaVersion":"scouterctl.result/v1","ok":true,"data":{},"executionComplete":true}
```

`data` is command-specific: an array for `object list`, otherwise an object. An empty array or an empty context map is a valid successful result. Nested `data.schemaVersion` values describe that payload (for example contexts or captures); they do not replace the outer result schema.

`ok: true` means data was produced. It does **not** imply exit 0. A partial result retains its data, has `executionComplete: false`, and exits 8. For example, an explicitly truncated `profile get` produces this result shape rather than an error object. Capture completion means the requested export finished; a completed capture can store a truncated profile and exit 0, while inspecting that profile exits 8. Inspect the operation's scope and the profile's `completeness` separately.

Completion never certifies traffic coverage, sampling, comparability or profile completeness. `unknown` is not a positive assertion. A result-list limit may truncate the displayed rows without making the underlying capture incomplete; use the command's `truncated` and summary fields.

## Errors and exit codes

```json
{"schemaVersion":"scouterctl.error/v1","type":"error","ok":false,"error":{"code":"INVALID_ARGUMENT","message":"Invalid command arguments; use --help"}}
```

The example message is illustrative. `error.code` is the machine-readable identifier; `error.message` is a human-readable string. An error object contains no result data. During a stream, previously emitted records remain on stdout but are not a complete result.

| Exit | Error code when an error object is emitted | Meaning |
| --- | --- | --- |
| 0 | None | Requested command completed; scope limitations still apply |
| 2 | `INVALID_ARGUMENT` | Invalid arguments, configuration or resume conditions |
| 3 | `AUTH_FAILED` | Authentication or session failure |
| 5 | `CONNECTION_FAILED` | Connection, socket or timeout failure |
| 6 | `PROTOCOL_ERROR` | Unsupported or undecodable response |
| 7 | `NOT_FOUND` | Requested profile/transaction unavailable |
| 8 | `PARTIAL_RESULT`, or a partial result object | Page/resource limit or incomplete operation |
| 9 | `VERSION_MISMATCH` | Unsupported Collector version |
| 10 | `IO_ERROR` | Artifact/transport I/O or integrity failure |

A mid-response EOF currently maps to `IO_ERROR` (10), whereas socket exceptions/timeouts map to `CONNECTION_FAILED` (5). Exit 8 therefore needs both the exit status and the envelope: it can carry available data or describe a failure without data. Treat unrecognized nonzero exits as failures rather than guessing a known category.

## Streaming commands

`xlog search` uses `scouterctl.stream/v1` on its first `start` event:

```text
{"type":"start","schemaVersion":"scouterctl.stream/v1","source":{...},"query":{...}}
{"type":"record","data":{...}}
{"type":"complete","executionComplete":true,"upstreamCoverage":"unknown","sampling":"unknown"}
```

The abbreviated objects illustrate event shape, not executable JSON fixtures. There are zero or more `record` events. `record` and `complete` events do not repeat `schemaVersion`; they inherit the stream contract from `start`.

A successful stream has exactly one final `complete` event and exit 0. Failure after streaming starts ends with the `scouterctl.error/v1` error object and a nonzero exit, without `complete`. Failure before streaming starts may emit only the error object. A page budget produces `PARTIAL_RESULT` and exit 8; earlier records must not be mistaken for the full query. A killed process or failed output transport may leave no terminal event.

A consumer should accept the stream schema, process records, then require both the final `complete` event and exit 0 before marking the query complete. Use `capture` and its checkpoints when resumable extraction is needed.

## Values and evolution

- `txid`, `gxid`, `caller`, and cursor transaction IDs are decimal strings, including negative values and the full signed 64-bit range. Never convert them through a floating-point number.
- Hashes, counts, durations and timestamps retain their command-defined numeric types and units. Timestamps such as `endTime`, `from` and `to` are epoch milliseconds; elapsed durations are milliseconds. Do not infer units from a display label.
- JSON booleans are booleans, not strings. `null` is not interchangeable with 0, an empty string, an empty array or an absent field. For example, `current: null` means no selected context; `updated: null` is not an update count of zero. Dictionary status and text availability must be read together.
- Object-member order and whitespace are not part of the contract. Consumers must tolerate added fields at every object level while validating fields they use. The current capture-file reader is stricter; JSON output tolerance does not change the [capture compatibility rules](capture-compatibility.md).
- Removing/renaming existing fields, changing their type, units or meaning, or changing established event semantics requires a new affected output schema version and documented migration. Existing error codes and exit meanings must not be silently repurposed. Enum/status additions that require new interpretation also need an explicit compatibility decision rather than being assumed safe.
- New commands and additive fields can be introduced without changing existing envelopes. Changing existing command syntax or defaults in a way that breaks scripts requires release notes and a migration path. This document does not promise indefinite support for every old release.

Use [Quick start](../QUICKSTART.md) for supported commands and data semantics, [profile completeness](profile-completeness.md) for truncation signals, and [architecture](architecture.md) for presentation/serialization ownership. Core and application models do not own JSON envelopes or numeric process exit codes.
