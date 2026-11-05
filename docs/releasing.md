# Release maintenance

Maintainer procedures for versioning, publication and recovery. For contribution setup and branch targets, see [Contributing](../CONTRIBUTING.md).

## Versioning and publication

`version.txt` is the source of the build version. Gradle uses it for CLI output, artifact names, and packaged metadata. README examples read it rather than duplicating a version number.

1. Promote `develop` into `master`. CI verifies the package before release-please runs.
2. Release-please maintains a PR against `master` with the proposed version, `CHANGELOG.md`, and `.release-please-manifest.json`. The manifest is release-please's bookkeeping; do not edit it independently of the version file.
3. Review and squash merge that release PR when ready to release. A successful build is followed by creation of a `vX.Y.Z` tag and GitHub Release, then attachment of the verified ZIP and `SHA256SUMS` from the same run. Releases below 1.0 are marked as prereleases.
4. Synchronize `master → develop` using a merge commit.

Check the generated candidate against all changes since the latest release tag. Release-please can stop scanning history at that release commit before reaching older feature commits newly promoted from `develop`. Preserving ancestry alone does not guarantee those commits appear in the release notes; the promotion merge commit must describe the release intent.

If a promotion produces no candidate, inspect the release job log before retrying. A reviewed recovery commit can use the [supported `Release-As` footer](https://github.com/googleapis/release-please#how-do-i-change-the-version-number) to request the intended version, with a user-facing Conventional Commit message describing the omitted feature or fix. Review the resulting changelog; do not rewrite release tags or edit version metadata independently.

Release PRs are not automatically merged. Existing tags and release assets must not be replaced.

The workflow uses only the repository's `GITHUB_TOKEN`. Allow GitHub Actions to create pull requests in the repository's Actions settings. Release PRs require the latest revision to pass the cross-platform checks before merge. Bot-created PR checks may require **Approve workflows to run**. Release assets are attached in the same workflow because tags/releases created by the default token do not trigger another workflow.

## Runner delays

A queued job is not a test failure. Follow [Runner delay recovery](ci-runner-recovery.md) when a required platform has not started. Keep all three platform gates: do not substitute a different architecture or publish an unchecked package to clear a queue.

## Recover an interrupted upload

For releases created by this automation, open **Actions → CI and release → Run workflow** on `master` and enter the existing tag, such as `v0.0.1`, in `release_tag`. Leaving it blank runs checks only.

The workflow checks out that tag, rebuilds and verifies its package, confirms the tag matches the built commit, and uploads missing assets. Existing assets must be byte-identical; they are never overwritten. If rebuilding produces different bytes, recover the original `release-package` artifact from the release run (retained for 14 days) and upload only the missing file. Do not retag an existing release.

Recovery verifies the historical tag on Linux and skips the new portable jobs because older tags may not contain their scripts or test configuration. This exception only repairs an existing tag; new release publication requires the Windows and macOS checks.

Actions are pinned to commit SHAs. Dependabot groups GitHub Actions updates into one weekly PR. No personal access token or additional release service is required.
