# Development and releases

Use Kotlin for new production code and tests. The existing Java reference probe is an independent protocol comparison tool; it is not a template for new application code. Write automated tests with Kotest `FunSpec` and Kotest matchers. Kotest runs on JUnit Platform on the JVM; JUnit Jupiter annotations and assertions are not used for new tests.

Use JDK 21 and the checked-in Gradle Wrapper:

```sh
./gradlew test pocRelease
bash .github/scripts/verify-package.sh
```

The Linux verification script requires Bash, Java, jq, unzip, and standard GNU utilities. CI builds the release package on Linux, then downloads that exact ZIP on Windows and macOS. Those jobs verify the checksum, archive contents, version/help, and run the complete Kotest suite against the extracted JAR in a path containing spaces. Each operating system uses its native JDK 21. Release automation waits for all three platforms. The suite also copies the JAR into a Korean installation directory and launches it by its ASCII filename, using UTF-8 argument files for Korean connection names, capture paths and service filters. Direct non-ASCII arguments to the Windows JDK launcher remain code-page dependent; see [Windows Unicode usage](QUICKSTART.md#windows-unicode-paths-and-arguments).

To test an extracted package locally, run `./gradlew test -PcliJar=/absolute/path/to/scouterctl-version-all.jar` (`./gradlew.bat` in PowerShell). The package version must match the checkout. The normal test task builds and uses the local JAR when this property is omitted.

The real Collector/Agent/Spring smoke tests require a separately built Scouter checkout and are not part of this CI workflow. The Kotest suite uses native pseudo-terminals (PTY on Linux/macOS, ConPTY on Windows) to exercise hidden password input, cancellation, and saved connections against a protocol fixture. This verifies console behavior, not a specific desktop terminal application or real Collector instrumentation on every OS. The terminal library is test-only and is not included in the release JAR.

The optional `recoverySmoke` task exercises real Collector stop/restart and capture resume; see [Capture recovery](docs/capture-recovery.md).

## Architecture boundaries

Follow the dependency rules in [Architecture](docs/architecture.md). Keep core models and application operations independent of CLI parsing, serialization, files and network clients. Implement external operations behind application-owned ports and construct concrete adapters in the composition root.

Add a command by separating argument interpretation, application work and presentation. Keep wire DTOs at the serialization boundary; a model refactor must preserve JSON fields, defaults, error codes and existing capture files. Use in-memory port tests for workflow rules and retain subprocess tests for the packaged CLI contract.

The normal Kotest suite includes compiled-class dependency checks and deliberate violation fixtures. Keep these checks enabled when adding packages or moving code. A passing package boundary check complements behavioral tests; it does not replace them.

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

Use one Unicode Gitmoji that matches the primary intent of each authored commit. Keep subjects concise and in English:

- `✨ Add offline profile inspection`
- `🐛 (capture): Preserve checkpoints on connection failure`
- `♻️ Extract profile lookup orchestration`
- `✅ Cover missing profile retries`
- `📝 Document offline inspection`
- `👷 Update package verification workflow`

For squash merges, use a Conventional Commit PR title with the Gitmoji **after** the type prefix, such as `feat: ✨ Add offline profile inspection` or `fix(capture): 🐛 Preserve checkpoints on connection failure`. Use that title as the squash commit subject so release-please can still determine the version bump:

- `fix: ...` for a patch release.
- `feat: ...` for a minor release.
- `feat!: ...` (or another type with `!`) for a breaking change. Before 1.0, breaking changes bump the minor version.
- `test: ...`, `docs: ...`, `ci: ...`, and `chore: ...` do not trigger a release on their own.

For `develop → master` promotions, choose the merge commit's Conventional Commit type from the unreleased changes: `feat: 🔀 Promote capture comparison` for new features, or `fix: 🔀 Promote connection fixes` for fixes only. Preserve the underlying commits with a merge commit. Use `chore: 🔀 Sync master into develop` for synchronization without a new release intent. Keep automated release-please and Dependabot messages in their generated format. Do not rewrite published history solely to add Gitmoji.

## Pull request descriptions

Choose one language; each template has headings and guidance only in that language:

- [English template](.github/PULL_REQUEST_TEMPLATE/english.md)
- [한국어 양식](.github/PULL_REQUEST_TEMPLATE/korean.md)

On GitHub, select the base and compare branches **before** choosing a language. In the PR description, open **Preview** and click **English** or **한국어** in the default selection guide. These links keep the current branch comparison and load the selected template. Write your description afterward; switching languages reloads the form. If GitHub restores an edited draft instead of the template, open the language link in a new tab. GitHub removes the template parameter when branches change, so select the language again if you change branches.

GitHub loads templates from the default branch (`master`). You can also load the desired local file directly, with `--base` set according to the branch rules above:

```sh
# English feature PR
gh pr create --base develop --template .github/PULL_REQUEST_TEMPLATE/english.md
# 한국어 feature PR
gh pr create --base develop --template .github/PULL_REQUEST_TEMPLATE/korean.md
```

Keep the body concise: explain the problem, resulting behavior, actual validation, and compatibility risks. Report untested items explicitly. Documentation-only changes need documentation checks, not runtime tests. Include only information safe to publish. Templates are guidance, not an automated merge gate.

GitHub에서 먼저 대상(base)·작업(compare) 브랜치를 선택하고, PR 본문의 **Preview** 탭에서 **English** 또는 **한국어**를 클릭해 주세요. 현재 브랜치를 유지한 채 선택한 언어의 양식만 불러옵니다. 본문은 언어 선택 후 작성하세요. 언어를 바꾸면 화면이 다시 열립니다. 수정한 초안이 양식 대신 복원되면 언어 링크를 새 탭으로 열어 주세요. 브랜치를 변경하면 GitHub가 언어 선택 값을 지우므로 언어를 다시 선택해 주세요.

웹 양식은 기본 브랜치(`master`)의 파일을 사용합니다. 위 명령으로 로컬 파일을 직접 선택할 수도 있습니다. `--base`는 위 브랜치 규칙에 맞게 지정하세요. 변경 이유·결과·실제 검증·호환성을 간결하게 적고 미검증 항목을 명시해 주세요. 문서만 바뀌었다면 문서 확인 결과만 적으면 됩니다. 공개 가능한 정보만 포함하며, 템플릿 자체가 병합을 자동으로 차단하지는 않습니다.

## Release process

`version.txt` is the source of the build version. Gradle uses it for CLI output, artifact names, and packaged metadata. README examples read it rather than duplicating a version number.

1. Promote `develop` into `master`. CI verifies the package before release-please runs.
2. Release-please maintains a PR against `master` with the proposed version, `CHANGELOG.md`, and `.release-please-manifest.json`. The manifest is release-please's bookkeeping; do not edit it independently of the version file.
3. Review and squash merge that release PR when ready to release. A successful build is followed by creation of a `vX.Y.Z` tag and GitHub Release, then attachment of the verified ZIP and `SHA256SUMS` from the same run. Releases below 1.0 are marked as prereleases.
4. Synchronize `master → develop` using a merge commit.

Check the generated candidate against all changes since the latest release tag. Release-please can stop scanning history at that release commit before reaching older feature commits newly promoted from `develop`. Preserving ancestry alone does not guarantee those commits appear in the release notes; the promotion merge commit must describe the release intent.

If a promotion produces no candidate, inspect the release job log before retrying. A reviewed recovery commit can use the [supported `Release-As` footer](https://github.com/googleapis/release-please#how-do-i-change-the-version-number) to request the intended version, with a user-facing Conventional Commit message describing the omitted feature or fix. Review the resulting changelog; do not rewrite release tags or edit version metadata independently.

The existing `v0.0.0` is the bootstrap baseline. It is not rebuilt or replaced by this setup. Release PRs are not automatically merged.

The workflow uses only the repository's `GITHUB_TOKEN`. Allow GitHub Actions to create pull requests in the repository's Actions settings. Bot-created PR checks may require **Approve workflows to run**; approve and check them before merging. Release assets are attached in the same workflow because tags/releases created by the default token do not trigger another workflow.

## Runner delays

A queued job is not a test failure. Follow [Runner delay recovery](docs/ci-runner-recovery.md) when a required platform has not started. Keep all three platform gates: do not substitute a different architecture or publish an unchecked package to clear a queue.

## Recover an interrupted upload

For releases created by this automation, open **Actions → CI and release → Run workflow** on `master` and enter the existing tag, such as `v0.0.1`, in `release_tag`. Leaving it blank runs checks only.

The workflow checks out that tag, rebuilds and verifies its package, confirms the tag matches the built commit, and uploads missing assets. Existing assets must be byte-identical; they are never overwritten. If rebuilding produces different bytes, recover the original `release-package` artifact from the release run (retained for 14 days) and upload only the missing file. Do not retag an existing release.

Recovery verifies the historical tag on Linux and skips the new portable jobs because older tags may not contain their scripts or test configuration. This exception only repairs an existing tag; new release publication requires the Windows and macOS checks.

Actions are pinned to commit SHAs. Dependabot groups GitHub Actions updates into one weekly PR. No personal access token or additional release service is required.
