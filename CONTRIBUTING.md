# Development and releases

Use JDK 21 and the checked-in Gradle Wrapper:

```sh
./gradlew test pocRelease
bash .github/scripts/verify-package.sh
```

The verification script requires Bash, Java, jq, unzip, and standard GNU utilities. CI runs on Linux and checks the CLI contract tests, packaged CLI version/help, archive contents, and checksum. The real Collector/Agent/Spring smoke tests require a separately built Scouter checkout and are not part of this CI workflow.

## Changes

Open a pull request against the default branch. Use a Conventional Commit title and squash merge so that the PR title becomes the commit subject:

- `fix: ...` for a patch release.
- `feat: ...` for a minor release.
- `feat!: ...` (or another type with `!`) for a breaking change. Before 1.0, breaking changes bump the minor version.
- `docs: ...`, `ci: ...`, and `chore: ...` do not trigger a release on their own.

The default branch currently is `codex/initial-poc`. If it is renamed, update the push branch filter in `.github/workflows/ci-release.yml` as well.

## Release process

`version.txt` is the source of the build version. Gradle uses it for CLI output, artifact names, and packaged metadata. README examples read it rather than duplicating a version number.

1. Merge changes into the default branch. CI verifies the package before release-please runs.
2. Release-please maintains a release PR with the proposed version, `CHANGELOG.md`, and `.release-please-manifest.json`. The manifest is release-please's bookkeeping; do not edit it independently of the version file.
3. Review and merge that PR when ready to release. A successful build is followed by creation of a `vX.Y.Z` tag and GitHub Release, then attachment of the verified ZIP and `SHA256SUMS` from the same run. Releases below 1.0 are marked as prereleases.

The existing `v0.0.0` is the bootstrap baseline. It is not rebuilt or replaced by this setup. Release PRs are not automatically merged.

The workflow uses only the repository's `GITHUB_TOKEN`. Allow GitHub Actions to create pull requests in the repository's Actions settings. Bot-created PR checks may require **Approve workflows to run**; approve and check them before merging. Release assets are attached in the same workflow because tags/releases created by the default token do not trigger another workflow.

## Recover an interrupted upload

For releases created by this automation, open **Actions → CI and release → Run workflow** on the default branch and enter the existing tag, such as `v0.0.1`, in `release_tag`. Leaving it blank runs checks only.

The workflow checks out that tag, rebuilds and verifies its package, confirms the tag matches the built commit, and uploads missing assets. Existing assets must be byte-identical; they are never overwritten. If rebuilding produces different bytes, recover the original `release-package` artifact from the release run (retained for 14 days) and upload only the missing file. Do not retag an existing release.

Actions are pinned to commit SHAs. Dependabot groups GitHub Actions updates into one weekly PR. No personal access token or additional release service is required.
