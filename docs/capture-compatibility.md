# Capture compatibility

Capture format versions are independent of the CLI release version. The manifest's required `schemaVersion` identifies the format of the whole bundle, including its JSONL pages. Do not rename the version to make an incompatible bundle appear readable.

## Supported formats

| Format | Contents | Current reader | Current writer / resume |
| --- | --- | --- | --- |
| `scouterctl.capture/v1` | XLogs only | Validation when resuming; not accepted by offline `inspect` commands | Creates and resumes v1 |
| `scouterctl.capture/v2` | XLogs and profiles from earlier releases | Offline inspect, search, summary and comparison | Read-only; resume returns exit 2 without changing files |
| `scouterctl.capture/v3` | XLogs and profiles with explicit completeness metadata | Offline inspect, search, summary and comparison | Creates and resumes v3 with `--include-profiles` |

A comparison can read v2 and v3 together. Its statistics still describe captured XLogs, not exhaustive profile steps or all upstream traffic.

The v3 layout, checksums, limits, text policy and checkpoint rules retain the v2 design. Profile records add `completeness` (`unknown` or `truncated`). Earlier strict readers cannot safely consume that additional field. New bundles therefore declare v3, allowing old readers to reject them at the manifest rather than failing partway through their pages. A rejected version does not mean the capture is damaged.

## Existing captures

Keep original v1/v2 directories. Reading a v2 bundle does not rewrite or upgrade it. Missing profile completeness is read as `unknown`; earlier captures did not retain enough control-step detail to reconstruct truncation reliably.

To continue an incomplete v2 capture, use the CLI version that created it, or start a new v3 capture in a different directory with the intended query and `--include-profiles`. The current CLI refuses v2 resume before writing any page or manifest, even if the capture is already complete. It does not offer automatic migration, downgrade, or in-place conversion. New capture contents still depend on what the Collector retains when they are requested.

## Evolution rules

- Require an explicit, recognized string `schemaVersion` before decoding version-specific manifest fields or reading pages. Missing, incorrectly typed and unsupported versions return exit 2; never guess the latest version.
- Keep existing field names, types, enum meanings, default behavior and page layout stable within a format version. Current capture readers are strict about unknown JSON fields; even an optional new stored field requires a new format version when older readers would reject it.
- Support older formats through explicit read paths. Do not silently discard unknown completeness values or reinterpret them as complete.
- Validate the original page bytes against their recorded checksums. Reading must not regenerate JSON or update hashes. Checksums detect changes; they do not authenticate who created a capture.
- Announce unsupported formats and migration paths in release notes before removing a documented read capability. This initial compatibility policy covers the formats above; it is not an unlimited future support promise.

These rules cover capture files. [Command output and exit codes](cli-contract.md) have separate versioning rules. See [Profile completeness](profile-completeness.md) for truncation and exit behavior.
