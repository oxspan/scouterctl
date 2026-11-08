# Profile completeness

`profile get` and offline `inspect` expose `profile.completeness` (the online profile is the result's `data` object):

| Value | Meaning |
| --- | --- |
| `truncated` | The supported Collector returned its explicit profile truncation control step. Some stored profile blocks were omitted from this response. |
| `unknown` | No recognized truncation signal is available. This does not certify a complete trace. |

The request's `max=1000` limits **stored profile blocks**, not decoded steps. A response containing 1,001 steps is not by itself evidence of truncation. For Collector 2.21.3, detection requires `StepControl`, code `0`, and its exact truncation marker. Arbitrary control messages and free text are not exposed.

Online `profile get` and offline `inspect` return exit `8` and result-level `executionComplete=false` for a known truncated profile. They still return the available profile data. `unknown` retains the previous exit behavior; success is not proof that all upstream activity was captured.

```sh
scouterctl profile get --date YYYYMMDD --txid TXID
scouterctl inspect --directory CAPTURE --txid TXID
```

## Capture compatibility and limits

The completeness value is stored with profiles in newly written v3 captures and survives text redaction during inspection. Existing captures without the field read as `unknown`; their former control-step text/code was not stored, so it cannot reliably be reconstructed. Reading an old capture does not rewrite it.

New profile bundles declare `scouterctl.capture/v3` because older strict readers cannot consume the new profile field. The current reader accepts existing v2 captures without rewriting them, but refuses v2 resume. Use the current reader for v3 captures and keep existing originals. XLog-only v1 captures are unchanged. See [Capture compatibility](capture-compatibility.md) for the version matrix and migration options.

Capture completion and search/summary/comparison completion retain their existing meanings. A completed XLog export does not certify complete profiles. Inspect a representative profile's completeness before interpreting its SQL steps as exhaustive. This change does not fetch unlimited blocks, automatically retry truncation, infer agent-side loss, or restore omitted data.
