# Contributing to scouterctl

Bug reports, documentation improvements and focused Kotlin changes are welcome. For vulnerabilities, follow [SECURITY.md](SECURITY.md) rather than posting details in an issue.

## Get started

Clone the repository and check out `develop`. Use JDK 21 and the checked-in Gradle Wrapper; a system Gradle installation is unnecessary.

```sh
./gradlew test fatJar
```

On Windows, use `./gradlew.bat test fatJar` in PowerShell. The ordinary suite uses protocol fixtures and does not require a running Scouter Collector.

Use focused tests while developing, for example `./gradlew test --tests 'dev.scouterctl.CliOutputContractTest'`. Before handing off packaging changes, run:

```sh
./gradlew pocRelease
bash .github/scripts/verify-package.sh
```

The package verification script requires Linux, Bash, JDK 21, jq, unzip and standard GNU utilities. Windows package verification uses `.github/scripts/verify-portable-package.ps1`; see [Local CI](docs/local-ci.md) for native Windows checks and the optional Linux act workflow. Real Collector/Agent/Spring checks are separate, opt-in integration scenarios described in [Capture recovery](docs/capture-recovery.md).

For matching older Collector/Agent releases on Java 8, see [Supported versions](docs/supported-versions.md#reproduce-the-checks). The optional fixture has a separate Java 8 compilation and test-only JDBC dependency.

## Code and tests

- Write production code and tests in Kotlin. Use Kotest `FunSpec` and Kotest matchers. Kotest uses JUnit Platform; new tests should not use JUnit Jupiter annotations or assertions.
- Follow the inward dependency rules in [Architecture](docs/architecture.md). Keep CLI parsing, JSON, files and network code outside core/application logic.
- Preserve the [CLI output contract](docs/cli-contract.md) and [capture compatibility rules](docs/capture-compatibility.md). Explain migrations when a change breaks either contract.
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

Use one language per PR. See the [English](.github/PULL_REQUEST_TEMPLATE/english.md) and [Korean](.github/PULL_REQUEST_TEMPLATE/korean.md) templates.

PRs to `develop` receive Linux tests and package checks. PRs to `master` also verify the same package and run tests on Windows and macOS. Check the latest revision's results before merging; local validation does not establish hosted CI success. See [Local CI](docs/local-ci.md) for the complete execution policy.

## Commit and merge conventions

Use one intention-matched Gitmoji and a concise English subject for authored commits, such as `✨ Add offline inspection`, `🐛 Preserve capture checkpoints`, or `📝 Clarify installation`.

Feature and hotfix PRs normally use squash merge. Give the PR a Conventional Commit title with the Gitmoji after the type, such as `feat: ✨ Add offline inspection` or `fix: 🐛 Preserve capture checkpoints`; use that title as the squash subject. `feat` requests a minor release, `fix` a patch, and `!` marks a breaking change (a minor bump before 1.0). `docs`, `test`, `ci` and `chore` do not request a release on their own.

Use merge commits for `develop → master` promotion and `master → develop` synchronization to preserve ancestry. A promotion title must reflect its release intent, such as `feat: 🔀 Promote capture comparison`; use `chore: 🔀 Sync master into develop` for synchronization. Keep generated release-please and Dependabot messages intact.

## Release maintenance

Maintainers: see [Release maintenance](docs/releasing.md) for versioning, release PRs, platform gates and interrupted-upload recovery. `version.txt` is the build version source; do not edit release tags or version metadata as an incidental contribution change.
