# Release maintenance

## Version source and release intent

`version.txt` and `.release-please-manifest.json` remain managed by release-please. Gradle derives command output, archive names and packaged metadata from that version. Release-please updates the version and changelog in a release PR; do not manually retag or overwrite an existing release.

The first stable release uses a one-time `Release-As: 1.0.0` footer in the promotion commit body. Preserve that body when squashing the preparation PR or include it in the `develop -> master` merge commit. Later releases return to normal Conventional Commit versioning. The repository config marks new releases as stable; existing published prereleases are unchanged. See [release-please version overrides](https://github.com/googleapis/release-please#how-do-i-change-the-version-number).

Feature work targets `develop`. Promote with a merge commit only after the candidate is approved. Merging the generated release PR is the publication step: release-please creates the tag/release and verified assets are attached. Repository visibility changes, npm publication and release approval are separate owner decisions.

## Local candidate, without publication

Build with JDK 21 and the pinned native toolchain:

```sh
./gradlew -PcandidateVersion=1.0.0 test verifyReleaseInputs verifyCommandRelease \
  -PzigHome=/path/to/zig-0.15.2 -PwindowsJdkHome=/path/to/windows-jdk-21
PACKAGE_VERSION=1.0.0 ALLOW_CANDIDATE=true bash .github/scripts/verify-release-files.sh
```

`candidateVersion` changes build output only. It generates a matching `version.txt` inside the ZIPs while leaving tracked release metadata intact. A candidate is not a published release. The uploader always requires tracked metadata and tag/commit agreement and never accepts this local override as publication authority.

`build/release/` contains exactly the Linux x64 ZIP, Windows x64 ZIP and their `SHA256SUMS`. Both ZIPs contain the same JVM libraries and metadata; Windows adds its native launcher/notices. Java is not bundled. See the [draft 1.0 release notes](releases/1.0.0.md).

For actual install/update/rollback/remove checks using two versioned command packages:

```sh
./gradlew -PcandidateVersion=1.0.0 installLifecycleSmoke \
  -PcurrentPackage=/path/to/scouterctl-1.0.0-linux-x64.zip \
  -PpreviousPackage=/path/to/scouterctl-0.3.0.zip
```

Use native Windows ZIPs when running on Windows. This operates only in a temporary directory and process-local PATH, preserves external configuration/data, and removes its own installations. A 0.x command package built from source is distinct from the published 0.x POC JAR asset.

## Required checks and artifacts

- PRs to `develop`: Linux full Kotest, canonical command distribution and executable input verification.
- PRs to `master`, master pushes and new-format release recovery: Linux checks plus native Windows command build, verification and full Kotest/native launcher tests.
- macOS is outside the initial 1.0 support and required release gate. Optional scale diagnostics retain their separate platform matrix; they are not release certification.

The Linux job builds `releaseInputs`: canonical JVM distribution, Linux ZIP, fat JAR for cross-platform tests, and checksums. The Windows job validates those inputs, builds only the native outer launcher using [Zig 0.15.2](https://ziglang.org/download/index.json), and assembles final command archives from the same Linux JVM payload. The final `release-commands` artifact is the only new-format publication input.

Linux builds use `test verifyReleaseInputs`; Windows uses `verifyReleaseInputs verifyCommandRelease -PreleaseInputDirectory=<download>` and runs `test` with the downloaded fat JAR and `windowsDistribution`. Action and toolchain versions are pinned; no extra release service or PAT is required. Local `act` exercises the Linux job; see [local CI](local-ci.md). Local execution does not establish hosted CI success or GitHub permissions.

## Upload invariants

The uploader checks tracked version/manifest, tag and exact built commit, archive version metadata and the canonical two-file checksum list. It validates all already-present assets before uploading anything. Existing assets must match byte-for-byte and are never overwritten. A partial upload can resume by attaching only missing assets.

## Recover an interrupted upload

Dispatch **CI and release** on `master` with an existing `release_tag`. It rebuilds that tag; it does not create a new version. New-format command releases require Linux and Windows checks again. Historical 0.x tags execute their original POC build/upload scripts and retain the Linux-only repair exception, since those tags do not contain command-package tasks.

If rebuilding an existing release changes bytes, recover the original matching artifact rather than overwriting an asset or moving its tag. Hosted artifacts are retained for 14 days. When jobs fail before starting because of account billing, fix the account or finish local preparation; do not report hosted checks as passed or silently bypass the required release gate.
