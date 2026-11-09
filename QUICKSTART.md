# Quick start

English | [한국어](QUICKSTART.ko.md)

Install the [Linux x64 or Windows x64 command package](docs/installation.md) with JDK 21, then check:

```sh
scouterctl --version
scouterctl --help
```

## 1. Connect to a Collector

Obtain the Collector TCP address and account from its administrator. The examples use `127.0.0.1:6100` and `observer`; replace them with your connection details. A Scouter Web API URL is not a Collector TCP endpoint.

```sh
scouterctl context add --name local --host 127.0.0.1 --port 6100 --user observer
scouterctl context use --name local
scouterctl doctor --interactive
scouterctl object list --interactive
```

Choose your application from `object list` and copy its `objHash`. `doctor` checks login and the Collector version; an empty object list means no targets were returned.

Passwords are not saved. For the following examples, set `SCOUTERCTL_PASSWORD` in your shell using the [Bash or PowerShell instructions](docs/connections.md#passwords-in-scripts). Use a trusted network or tunnel for Collector TCP.

## 2. Read current state

Replace `123456` with the selected object's hash.

```sh
scouterctl collector status --no-input
scouterctl agent status --object-hash 123456 --no-input
scouterctl counter get --object-hash 123456 --counter HeapUsed --no-input
```

See [status, settings and current counters](docs/inspection.md) for available fields and missing-value behavior.

## 3. Save transactions and profiles

Use `scouterctl object list --date 20261107 --no-input` to find an `objHash` recorded on that storage date, even after a Collector restart. Dated results use `alive: false` because the stored list has no live status. Use `object list` without a date to check current status.

Use the Collector's storage date and timezone. This example assumes UTC. Create the parent `captures` directory if it does not exist, and use a new capture directory:

```sh
mkdir captures
scouterctl capture --object-hash 123456 --date 20261107 \
  --from 2026-11-07T09:00:00Z --to 2026-11-07T09:10:00Z --timezone UTC \
  --include-profiles --directory captures/incident-001 --no-input
```

The capture result reports `storageDateVerification: "unverified"`. Check the supplied storage date when a completed query returns no records.

Repeat the same command with `--resume` after interruption or partial completion. Add `--include-text` at capture time only if you intend to store potentially sensitive service names, SQL and error text. See [capture and offline analysis](docs/captures.md).

## 4. Inspect without a Collector

```sh
scouterctl inspect summary --directory captures/incident-001
scouterctl inspect search --directory captures/incident-001 --errors-only
scouterctl inspect --directory captures/incident-001 --txid '<TXID>'
```

Copy a transaction ID from the search result for the last command. Offline commands need only the saved files and JDK 21; they do not connect or read credentials.

## 5. Export stored counters

```sh
scouterctl counter export --object-hash 123456 --counter HeapUsed \
  --from 2026-11-06T00:00:00Z --to 2026-11-08T00:00:00Z --timezone UTC \
  --directory captures/heap-export --max-chunks 24 --no-input
```

When the chunk budget is reached (exit `8`), repeat with the same query and `--resume`. Missing samples are not filled with zero. See [single-day counter history](docs/counter-history.md) and [resumable counter export](docs/counter-export.md).

## Results and next steps

See [command and output examples](docs/examples.md) for actual login, counter, SQL/error, JSONL and error responses.

Online results use JSON or JSONL. Read the exit code and completion fields; successful extraction does not prove that the Collector retained all traffic. Transaction IDs are strings and must not be converted through floating-point numbers.

Clear `SCOUTERCTL_PASSWORD` when finished. See [output and exit codes](docs/cli-contract.md), [connection troubleshooting](docs/connections.md#connection-troubleshooting), [supported versions](docs/supported-versions.md), or the [documentation index](docs/README.md).
