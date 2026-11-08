# Command and output examples

English | [한국어](ko/examples.md)

These are outputs from test Collectors running 2.8.1 and 2.21.3. IDs, times and values vary by environment. Sections marked “selected fields” omit fields from the real response; the JSONL example includes every event.

For online commands, [select a connection and provide its password](connections.md). Replace sample object hashes, dates and transaction IDs with your own. `inspect` only needs a saved capture.

## Connection check

```sh
scouterctl doctor --interactive
```

A successful `doctor` response (full output, exit `0`):

```json
{
  "schemaVersion": "scouterctl.result/v1",
  "ok": true,
  "data": {
    "serverId": "command-fixture",
    "version": "2.8.1 2020-05-14 23:36 GMT",
    "timezoneDisplayName": "Coordinated Universal Time",
    "group": "guest",
    "source": "collector-tcp",
    "effectivePermission": "unknown",
    "nativeTls": false
  },
  "executionComplete": true
}
```

`effectivePermission: "unknown"` does not establish an administrator role. The result checks login and the Collector version, not Agent health.

## Current heap usage

```sh
scouterctl counter get --object-hash 970227877 --counter HeapUsed --no-input
```

Selected fields from a successful response (exit `0`):

```json
{
  "ok": true,
  "data": {
    "counters": [
      {
        "name": "HeapUsed",
        "displayName": "Heap Used",
        "unit": "MB",
        "value": "9.86953",
        "availability": "available"
      }
    ]
  },
  "executionComplete": true
}
```

The value is a decimal string and the unit is `MB`. `available` also includes genuine zero; an unavailable reading has a null value. These are current cached values, not proof of freshness.

## SQL and error profile

```sh
scouterctl inspect --directory captures/incident-001 --txid -8863176071656033104 --include-text
```

Use a capture containing profiles and text, created with `--include-profiles --include-text`. The saved v2 capture used here remains readable by the current CLI.

Selected fields from the result (exit `0`). The exception text is shortened; bound values and the stack trace are omitted from this example:

```json
{
  "ok": true,
  "data": {
    "record": {
      "xlog": {
        "txid": "-8863176071656033104"
      },
      "profileStatus": "available",
      "profile": {
        "steps": [
          {
            "type": "SQL3",
            "sql": {
              "elapsedMs": 1,
              "statement": {
                "hash": -886850054,
                "text": "insert into fixture_rows(id, marker) values (?, ?)",
                "status": "resolved"
              },
              "error": {
                "status": "resolved",
                "text": "org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException: Unique index or primary key violation [details omitted]"
              }
            }
          }
        ],
        "completeness": "unknown"
      }
    }
  },
  "executionComplete": true
}
```

SQL uses placeholders here, but exception text can still contain bound values. Include text only when appropriate. `executionComplete` does not make profile completeness known; this older capture reports `unknown`. See [profile completeness](profile-completeness.md).

## Stored counter history

```sh
scouterctl counter history --object-hash 1533469885 --counter HeapUsed --date 20261107 --from 2026-11-07T00:00:00.000Z --to 2026-11-07T05:30:13.438Z --timezone UTC --no-input
```

A complete JSONL response (exit `0`), with one JSON object per line:

```jsonl
{"type":"start","schemaVersion":"scouterctl.counter-stream/v1","source":{"serverId":"command-fixture","version":"2.8.1 2020-05-14 23:36 GMT","timezoneDisplayName":"Coordinated Universal Time","group":"guest","source":"collector-tcp","effectivePermission":"unknown","nativeTls":false},"query":{"date":"20261107","from":1794009600000,"to":1794029413438,"timezone":"UTC","objHash":1533469885,"counter":"HeapUsed"},"target":{"objHash":1533469885,"objName":"/scouterctl-test/command-fixture","objType":"java"},"counter":{"name":"HeapUsed","displayName":"Heap Used","unit":"MB"},"resolution":"stored-realtime","intervalMs":null,"missingValues":"omitted","metadataScope":"current-collector-definition"}
{"type":"record","data":{"time":1794029400034,"value":"8.846268","availability":"available"}}
{"type":"record","data":{"time":1794029402033,"value":"9.687073","availability":"available"}}
{"type":"record","data":{"time":1794029404034,"value":"14.435959","availability":"available"}}
{"type":"record","data":{"time":1794029406035,"value":"15.197998","availability":"available"}}
{"type":"record","data":{"time":1794029408036,"value":"15.575096","availability":"available"}}
{"type":"record","data":{"time":1794029410037,"value":"16.062645","availability":"available"}}
{"type":"record","data":{"time":1794029412038,"value":"16.062645","availability":"available"}}
{"type":"complete","executionComplete":true,"records":7,"upstreamCoverage":"unknown","sampling":"unknown"}
```

Require a final `complete` event and exit `0`. The seven records are the samples returned for this query, not a count of requests or proof of lossless collection. Times are epoch milliseconds; missing samples are not filled with zero.

## Missing password

```sh
scouterctl doctor --host 127.0.0.1 --port 6100 --user observer --no-input
```

With `SCOUTERCTL_PASSWORD` unset and no terminal input allowed, this returns exit `2` before connecting:

```json
{
  "schemaVersion": "scouterctl.error/v1",
  "type": "error",
  "ok": false,
  "error": {
    "code": "INVALID_ARGUMENT",
    "message": "Set SCOUTERCTL_PASSWORD, or use --interactive on a terminal for hidden input"
  }
}
```

Provide the password through your secret mechanism, or use `--interactive` in a terminal. Command errors are JSON on stdout; read the exit code too.

See [JSON output and exit codes](cli-contract.md), [the quick start](../QUICKSTART.md), and [supported Scouter 2.x releases](supported-versions.md).
