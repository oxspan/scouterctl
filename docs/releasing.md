# Releasing

## Version and branches

`version.txt` is the build version. Release-please updates it together with `.release-please-manifest.json` and `CHANGELOG.md`; do not change these as an incidental feature edit.

1. Merge feature work into `develop`, then promote `develop` to `master` with a merge commit and an appropriate Conventional Commit title.
2. The **CI and release** workflow checks Linux and Windows and creates or updates the release PR.
3. Review its version, changelog, release notes and checks. Merging the release PR is the publication decision: the workflow creates the tag/release and attaches the command packages.
4. Merge `master` back into `develop` so the next feature starts from the released metadata.

Both branches require pull requests, resolved review conversations, and an up-to-date branch. The required GitHub Actions checks are `Build and test` and `Windows command package`. CI runs Windows packaging and tests on PRs into both branches, then verifies the resulting npm archives on Linux. The single-maintainer policy requires no additional approving reviewer and grants no ruleset bypass.

Release-please uses `GITHUB_TOKEN`. Its PR workflows may require a maintainer to select **Approve workflows to run** before CI starts; see [GitHub's token trigger rules](https://docs.github.com/en/actions/concepts/security/github_token#when-github_token-triggers-workflow-runs). If no PR check run exists, close and reopen the PR as the maintainer to trigger it. Wait for the required checks to pass before merging. Do not bypass checks to publish. Dependabot security PRs may target `master` directly; merge their fixes back into `develop` afterwards.

`feat` requests a minor release, `fix` a patch, and a breaking-change marker requests a major release. Documentation, test and CI changes alone do not request a release. For an intentional version override, preserve a one-time `Release-As: X.Y.Z` footer in the promotion commit; do not pin a permanent override in the config.

## Packages and checks

The final `build/release/` directory contains two platform ZIPs and `SHA256SUMS`. Linux and Windows share the same JVM payload. Java is not bundled. `npmPackage` packs that payload into `build/npm/scouterctl-<version>.tgz` without install scripts or external downloads.

The Linux job runs `test verifyReleaseInputs` and saves a canonical JVM distribution, Linux ZIP, test JAR and checksums. Windows downloads those inputs, builds the native launcher, verifies both command archives and runs the full tests against the downloaded JAR and native command. The resulting `release-commands` artifact is the publication input. macOS scale diagnostics are separate from release support.

Windows CI also installs a pinned, unmodified Scoop and Java bucket in the
workspace. With Java absent from PATH, it installs the Java dependency, checks
the default directory junctions, upgrades the published 1.0.0 package to the
candidate ZIP, and verifies Unicode configuration survives removal. Local ZIPs
use file URLs in temporary manifests; the repository's bucket remains unchanged.
This check runs only on disposable GitHub-hosted Windows runners and restores
the user's PATH and JAVA_HOME. Its transcript is saved as `scoop-installation-log`.

For local packaging, with JDK 21:

```sh
./gradlew verifyReleaseInputs
./gradlew verifyCommandRelease -PzigHome=/path/to/zig-0.15.2 \
  -PwindowsJdkHome=/path/to/windows-jdk-21
```

On Windows use `gradlew.bat`; `windowsJdkHome` defaults to the JDK running Gradle. The native build requires Zig 0.15.2 and Windows JDK 21 headers. See [local CI](local-ci.md) for native test commands and [testing](testing.md) for integration checks.

For a future local candidate, `-PcandidateVersion=1.0.1` changes build output without changing tracked release metadata. It does not authorize publication. Install/update/rollback/removal can be checked with `installLifecycleSmoke -PcurrentPackage=/path/to/new.zip -PpreviousPackage=/path/to/old.zip`, using two actual command packages for the current OS.

## Package managers

Java 21 remains an external dependency. A Linux jlink sample using the modules
reported by jdeps takes about 44 MB before adding the application; the current
command ZIP is about 4 MB. Keeping Java separate also lets Homebrew/Scoop update
it independently. npm users install Java themselves. Bundling a runtime would
require platform builds, license notices and a separate Java security-update
schedule; it is not part of this release.

`Formula/scouterctl.rb` and `bucket/scouterctl.json` make this repository the
Homebrew tap and Scoop bucket. They reference an existing release, not the
unreleased source version. Templates live under `packaging/`. Generate both from
actual, verified ZIPs with:

```sh
bash .github/scripts/generate-package-manifests.sh <version> build/release build/package-index
```

After attaching release assets, CI opens a PR updating these two files. An older
release repair cannot downgrade the index. Review and merge this PR to make the
release available through those managers. Run the CI and release workflow on
the update branch manually before merging: GitHub does not start PR workflows
for a PR opened with `GITHUB_TOKEN`. Do not update another tap or bucket repository.

CI builds canonical JVM distributions for `0.0.0` and the release version on
Linux. Windows builds the native command, then packs both npm archives using
those JVM distributions and the same source revision's native launcher. This
avoids Java's Windows argument conversion losing non-ASCII text. The npm package
includes the native launcher's license notices. Both Windows and Linux install
and execute the exact same npm archives before publication, and verify real
global/project install, upgrade, downgrade, removal, Unicode arguments, missing
Java and exit codes on Linux and Windows. Run the same checks locally with:

```sh
./gradlew npmPackage -PcandidateVersion=0.0.0 -PnpmWindowsDistribution=/path/to/windows.zip
./gradlew npmPackage verifyNpmPackage -PnpmWindowsDistribution=/path/to/windows.zip
```

Use a Windows ZIP built from the same source revision. Its launcher gets its
displayed version from the JVM payload, so the test fixture can share it. When
`npmWindowsDistribution` is omitted, Gradle builds the Windows ZIP and requires
the native toolchain options above. `npmDistribution` can select an already
verified canonical JVM ZIP instead of rebuilding it; its version must match.

The npm publish job is disabled until `NPM_PUBLISH_ENABLED=true` is configured.
Before enabling it, an npm owner must claim `scouterctl` with the first verified
archive, then configure an npm trusted publisher for repository
`oxspan/scouterctl`, workflow `ci-release.yml`, environment `npm`. Create that
GitHub environment with the desired deployment restrictions. CI uses OIDC and
provenance, with no long-lived npm token. It publishes only an actual release
and refuses to replace an existing npm version with different bytes. Rerunning
the same release repair retries missing publications. This setup and registry
publication require maintainer access; local `npm pack` and install checks do not.

## Publish and recover assets

The uploader requires agreement between tracked version/manifest, tag and built commit, archive metadata and checksums. It compares all existing assets before uploading anything; identical files are kept and differing files stop publication. Never move a release tag or overwrite a published package.

To repair missing assets on an existing release, dispatch **CI and release** on `master` with its `release_tag`. The workflow checks out and rebuilds that tag without creating a new version. Current command releases require Linux and Windows checks. Historical 0.x tags keep their original JAR package format and upload procedure.

If rebuilding changes package bytes, recover the original matching artifact. Workflow artifacts are retained for 14 days. See [CI troubleshooting](ci-runner-recovery.md) for runner, account and test failures. A local pass is not a hosted CI pass; any release exception needs an explicit maintainer decision and matching platform/source/artifact evidence.
