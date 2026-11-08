# Documentation

English | [한국어](ko/README.md)

Start with [installation](installation.md) and the [quick start](../QUICKSTART.md).

## Using scouterctl

| Guide | Contents |
| --- | --- |
| [Connections and authentication](connections.md) | Saved contexts, password input, automation and connection troubleshooting |
| [Collector, Agent and current counters](inspection.md) | Status, runtime settings and current metrics |
| [Capture and inspect transactions](captures.md) | XLogs, profiles, offline search, service summaries and comparison |
| [Counter history](counter-history.md) | Single-day stored samples as JSONL |
| [Counter export](counter-export.md) | Multi-day files, checkpoints and resume |
| [Supported versions](supported-versions.md) | Collector, Agent, JVM and platform scope |

## Output and saved files

- [JSON output and exit codes](cli-contract.md)
- [Capture formats and older files](capture-compatibility.md)
- [Interrupted capture recovery](capture-recovery.md)
- [Profile completeness](profile-completeness.md)
- [Security](../SECURITY.md)

## Development and maintenance

- [Contributing](../CONTRIBUTING.md) · [Architecture](architecture.md)
- [Testing](testing.md) · [Local CI](local-ci.md) · [CI troubleshooting](ci-runner-recovery.md)
- [Scale measurements](capture-scale-validation.md) · [Releasing](releasing.md)
- [Third-party library](../vendor/README.md) · [Changelog](../CHANGELOG.md) · [License](../LICENSE)

## Earlier packages

[Legacy JAR instructions](legacy-jar.md) apply to 0.x packages. See [1.0.0 release notes](releases/1.0.0.md) for the move to command ZIPs.
