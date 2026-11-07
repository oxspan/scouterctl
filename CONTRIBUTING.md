# Contributing to scouterctl

Bug reports, documentation improvements and focused Kotlin changes are welcome. For vulnerabilities, follow [SECURITY.md](SECURITY.md) rather than posting details in an issue.

## Get started

Clone the repository and check out `develop`. Use JDK 21 and the checked-in Gradle Wrapper. Linux checks also require Bash, `jq`, `sha256sum` and `unzip` on PATH.

```sh
git clone --branch develop https://github.com/oxspan/scouterctl.git
cd scouterctl
./gradlew test
```

Use `./gradlew.bat test` in Windows PowerShell. Ordinary tests use local fixtures; they do not require a Scouter account or download Collector distributions. While developing, select a focused test with `--tests 'dev.scouterctl.CliOutputContractTest'`.

To build and verify the Linux command package:

```sh
./gradlew verifyReleaseInputs
```

For a local Linux installation, use `./gradlew installDist` and run `build/install/scouterctl/bin/scouterctl`. Native Windows packaging additionally requires the pinned toolchain; see [releasing](docs/releasing.md). Real Collector/Agent/Spring checks are opt-in and described in [testing](docs/testing.md). Use [local CI](docs/local-ci.md) to run the workflow build with act when needed.

## Code and tests

- Implement CLI behavior in Kotlin. Use Kotest `FunSpec` and matchers; JUnit Platform runs Kotest, while test APIs remain Kotest. The native [Windows launcher](https://github.com/oxspan/scouterctl/blob/master/native/windows/README.md) is a separate platform boundary.
- Follow the inward dependency rules in [Architecture](docs/architecture.md). Keep CLI parsing, JSON, files and network code outside core/application logic.
- Preserve the [CLI output format](docs/cli-contract.md) and [capture compatibility rules](docs/capture-compatibility.md). Explain migrations when a change breaks either format.
- Test observable behavior with the existing fixtures and subprocess harnesses. Choose checks appropriate to the change; documentation-only edits do not need new runtime tests.
- Keep credentials, real captures, local configuration and generated evidence out of commits. Use synthetic, sanitized examples in reports and fixtures.

## Branches and pull requests

| Change | Start from | PR target |
| --- | --- | --- |
| Feature, regular fix, tests or documentation (`feature/*`) | `develop` | `develop` |
| Urgent release fix (`hotfix/*`) | `master` | `master` |
| Release-ready integration | `develop` | `master` |

`master` is the default and release branch; `develop` integrates upcoming changes. Release branches are not currently used. Keep each PR focused and explain the problem, resulting behavior, validation actually performed, and compatibility risks.

Choose the base and compare branches before selecting **English** or **한국어** from the default PR description's **Preview** tab. Switching languages reloads the form, so select a language before writing the description. If GitHub restores a draft, open the language link in a new tab; reselect it after changing branches. Templates are loaded from the default branch. Alternatively, use a local template:

```sh
gh pr create --base develop --template .github/PULL_REQUEST_TEMPLATE/english.md
gh pr create --base develop --template .github/PULL_REQUEST_TEMPLATE/korean.md
```

Use one language per PR. See the [English](https://github.com/oxspan/scouterctl/blob/master/.github/PULL_REQUEST_TEMPLATE/english.md) and [Korean](https://github.com/oxspan/scouterctl/blob/master/.github/PULL_REQUEST_TEMPLATE/korean.md) templates.

PRs to `develop` receive Linux tests and package checks. PRs to `master` also verify the same package and run tests on Windows. Check the latest revision's results before merging; local validation does not establish hosted CI success. See [Local CI](docs/local-ci.md) for the complete execution policy.

## Commit and merge conventions

Use one intention-matched Gitmoji and a concise English subject for authored commits, such as `✨ Add offline inspection`, `🐛 Preserve capture checkpoints`, or `📝 Clarify installation`.

Feature and hotfix PRs normally use squash merge. Give the PR a Conventional Commit title with the Gitmoji after the type, such as `feat: ✨ Add offline inspection` or `fix: 🐛 Preserve capture checkpoints`; use that title as the squash subject. `feat` requests a minor release, `fix` a patch, and `!` marks a breaking change (a major release for the current stable series). `docs`, `test`, `ci` and `chore` do not request a release on their own.

Use merge commits for `develop → master` promotion and `master → develop` synchronization to preserve ancestry. A promotion title must reflect its release intent, such as `feat: 🔀 Promote capture comparison`; use `chore: 🔀 Sync master into develop` for synchronization. Keep generated release-please and Dependabot messages intact.

## Release maintenance

Maintainers: see [Release maintenance](docs/releasing.md) for versioning, release PRs, platform gates and interrupted-upload recovery. `version.txt` is the build version source; do not edit release tags or version metadata as an incidental contribution change.
