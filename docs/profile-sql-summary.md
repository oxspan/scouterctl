# Group profile SQL steps

English | [한국어](ko/profile-sql-summary.md)

Use `--group-sql` when repeated SQL makes a profile too large to read:

```sh
scouterctl profile get --date YYYYMMDD --txid TXID --group-sql
scouterctl inspect --directory CAPTURE --txid TXID --group-sql
```

The option is accepted only by `profile get` and `inspect`. Add `--include-text` to include resolved SQL statements; offline inspection also requires a capture that stored text. Without `--group-sql`, both commands return their usual individual steps. Capture files and their schemas are unchanged, and inspection never rewrites them.

## Output

Online `data`, or offline `data.record.profile`, contains a `scouterctl.profile-sql-summary/v1` object instead of individual profile steps. The outer result envelope remains `scouterctl.result/v1`. Offline capture, XLog and availability metadata keep their existing fields. An unavailable offline profile remains `null`.

Example summary with text omitted:

```json
{
  "txid": "9223372036854775804",
  "completeness": "unknown",
  "textIncluded": false,
  "totalSteps": 3004,
  "sqlSteps": 3000,
  "nonSqlSteps": 4,
  "groups": [
    {"hash":123,"statement":null,"statementConflict":false,"count":3000,"elapsedTotalMs":6000,"maxElapsedMs":2,"errors":30}
  ],
  "schemaVersion": "scouterctl.profile-sql-summary/v1",
  "basis": "decoded-profile-steps"
}
```

Each group represents one signed SQL hash across all decoded SQL step variants. Groups are sorted by descending `elapsedTotalMs`, then ascending signed hash. `count` counts SQL steps, `elapsedTotalMs` sums their elapsed milliseconds using a 64-bit integer, `maxElapsedMs` is their largest elapsed value, and `errors` counts steps with a nonzero error hash. Original signed duration values are retained. These are decoded-step statistics, not affected-row counts or transaction wall time; overlapping steps can make their sum exceed transaction elapsed time. `txid` remains a decimal string.

With `--include-text`, `statement` has the usual dictionary `hash`, `text` and `status`. Missing text is unresolved. If multiple distinct resolved statements share a hash, the group has `statementConflict: true` and unresolved text rather than choosing one. Dictionary entries for a different hash are ignored. Without `--include-text`, statements are `null` and text conflicts are not reported. Error text, bind values, individual step order and non-SQL step details are not part of the summary; request the default profile output when you need step details.

## Limits and completion

Output grows with the number of distinct SQL hashes. Repeated statements benefit most; this mode imposes no step or group cap and does not reduce Collector transfer or profile decoding. It cannot restore missing steps or establish that a trace is complete.

The original [profile completeness](profile-completeness.md) and exit behavior apply: known truncation returns available groups with `completeness: "truncated"`, `executionComplete: false` and exit 8. `unknown` does not certify a complete trace. Offline inspection still validates the entire capture before returning a summary, including pages after the selected transaction. Incomplete captures and unavailable profiles retain their existing exit 8 behavior; corrupt capture data remains an error.
