# Development and releases

Use Kotlin for new production code and tests. The existing Java reference probe is an independent protocol comparison tool; it is not a template for new application code. Write automated tests with Kotest `FunSpec` and Kotest matchers. Kotest runs on JUnit Platform on the JVM; JUnit Jupiter annotations and assertions are not used for new tests.

Use JDK 21 and the checked-in Gradle Wrapper:

```sh
./gradlew test pocRelease
bash .github/scripts/verify-package.sh
```

The verification script requires Bash, Java, jq, unzip, and standard GNU utilities. CI runs on Linux and checks the CLI contract tests, packaged CLI version/help, archive contents, and checksum. The real Collector/Agent/Spring smoke tests require a separately built Scouter checkout and are not part of this CI workflow.

## Branches

This project uses a Git Flow variant without release branches:

| Branch | Purpose | Start from | PR target |
| --- | --- | --- | --- |
| `master` | Default branch; release-ready code and release automation | — | — |
| `develop` | Integration of upcoming changes | `master` | `master` when ready |
| `feature/<topic>` | Features, fixes, tests, and maintenance | `develop` | `develop` |
| `hotfix/<topic>` | Urgent fixes to release-ready code | `master` | `master` |

Create `feature/*` and `hotfix/*` branches as needed; they are not permanent branches named `feature` and `hotfix`.

- Squash merge feature PRs into `develop`, and hotfix PRs into `master`.
- Use a **merge commit**, not squash or rebase, for `develop → master` promotion and `master → develop` synchronization. This preserves shared ancestry and the individual Conventional Commits that release-please reads.
- After merging a hotfix or a release PR into `master`, synchronize `master → develop` with a merge-commit PR. This carries fixes, version changes, and changelog entries back into development.
- Dependabot targets `develop`. Release-please targets `master`.
- CI runs on PRs targeting either long-lived branch and on pushes to both. The PR route check rejects feature PRs targeting `master`. Publishing runs only from `master`.

A separate `release/*` branch is unnecessary while promotion to `master` is followed by a short release review. Revisit it if a release needs extended stabilization while unrelated work continues on `develop`, or if multiple release lines must be maintained. The release-please PR branch only prepares version metadata; it is not a stabilization branch.

## Commit messages

Use a Conventional Commit PR title for squash merges:

- `fix: ...` for a patch release.
- `feat: ...` for a minor release.
- `feat!: ...` (or another type with `!`) for a breaking change. Before 1.0, breaking changes bump the minor version.
- `test: ...`, `docs: ...`, `ci: ...`, and `chore: ...` do not trigger a release on their own.

For promotion and synchronization PRs, use descriptive titles such as `chore: promote develop to master` and preserve the underlying commits with a merge commit.

## Release process

`version.txt` is the source of the build version. Gradle uses it for CLI output, artifact names, and packaged metadata. README examples read it rather than duplicating a version number.

1. Promote `develop` into `master`. CI verifies the package before release-please runs.
2. Release-please maintains a PR against `master` with the proposed version, `CHANGELOG.md`, and `.release-please-manifest.json`. The manifest is release-please's bookkeeping; do not edit it independently of the version file.
3. Review and squash merge that release PR when ready to release. A successful build is followed by creation of a `vX.Y.Z` tag and GitHub Release, then attachment of the verified ZIP and `SHA256SUMS` from the same run. Releases below 1.0 are marked as prereleases.
4. Synchronize `master → develop` using a merge commit.

The existing `v0.0.0` is the bootstrap baseline. It is not rebuilt or replaced by this setup. Release PRs are not automatically merged.

The workflow uses only the repository's `GITHUB_TOKEN`. Allow GitHub Actions to create pull requests in the repository's Actions settings. Bot-created PR checks may require **Approve workflows to run**; approve and check them before merging. Release assets are attached in the same workflow because tags/releases created by the default token do not trigger another workflow.

## Recover an interrupted upload

For releases created by this automation, open **Actions → CI and release → Run workflow** on `master` and enter the existing tag, such as `v0.0.1`, in `release_tag`. Leaving it blank runs checks only.

The workflow checks out that tag, rebuilds and verifies its package, confirms the tag matches the built commit, and uploads missing assets. Existing assets must be byte-identical; they are never overwritten. If rebuilding produces different bytes, recover the original `release-package` artifact from the release run (retained for 14 days) and upload only the missing file. Do not retag an existing release.

Actions are pinned to commit SHAs. Dependabot groups GitHub Actions updates into one weekly PR. No personal access token or additional release service is required.
