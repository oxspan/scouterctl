# Releasing

## Version and branches

`version.txt` is the build version. Release-please updates it together with `.release-please-manifest.json` and `CHANGELOG.md`; do not change these as an incidental feature edit.

1. Merge feature work into `develop`, then promote `develop` to `master` with a merge commit and an appropriate Conventional Commit title.
2. The **CI and release** workflow checks Linux and Windows and creates or updates the release PR.
3. Review its version, changelog, release notes and checks. Merging the release PR is the publication decision: the workflow creates the tag/release and attaches the command packages.
4. Merge `master` back into `develop` so the next feature starts from the released metadata.

`feat` requests a minor release, `fix` a patch, and a breaking-change marker requests a major release. Documentation, test and CI changes alone do not request a release. For an intentional version override, preserve a one-time `Release-As: X.Y.Z` footer in the promotion commit; do not pin a permanent override in the config.

## Packages and checks

The final `build/release/` directory contains two platform ZIPs and `SHA256SUMS`. Linux and Windows share the same JVM payload. Java is not bundled.

The Linux job runs `test verifyReleaseInputs` and saves a canonical JVM distribution, Linux ZIP, test JAR and checksums. Windows downloads those inputs, builds the native launcher, verifies both command archives and runs the full tests against the downloaded JAR and native command. The resulting `release-commands` artifact is the publication input. macOS scale diagnostics are separate from release support.

For local packaging, with JDK 21:

```sh
./gradlew verifyReleaseInputs
./gradlew verifyCommandRelease -PzigHome=/path/to/zig-0.15.2 \
  -PwindowsJdkHome=/path/to/windows-jdk-21
```

On Windows use `gradlew.bat`; `windowsJdkHome` defaults to the JDK running Gradle. The native build requires Zig 0.15.2 and Windows JDK 21 headers. See [local CI](local-ci.md) for native test commands and [testing](testing.md) for integration checks.

For a future local candidate, `-PcandidateVersion=1.0.1` changes build output without changing tracked release metadata. It does not authorize publication. Install/update/rollback/removal can be checked with `installLifecycleSmoke -PcurrentPackage=/path/to/new.zip -PpreviousPackage=/path/to/old.zip`, using two actual command packages for the current OS.

## Publish and recover assets

The uploader requires agreement between tracked version/manifest, tag and built commit, archive metadata and checksums. It compares all existing assets before uploading anything; identical files are kept and differing files stop publication. Never move a release tag or overwrite a published package.

To repair missing assets on an existing release, dispatch **CI and release** on `master` with its `release_tag`. The workflow checks out and rebuilds that tag without creating a new version. Current command releases require Linux and Windows checks. Historical 0.x tags keep their original JAR package format and upload procedure.

If rebuilding changes package bytes, recover the original matching artifact. Workflow artifacts are retained for 14 days. See [CI troubleshooting](ci-runner-recovery.md) for runner, account and test failures. A local pass is not a hosted CI pass; any release exception needs an explicit maintainer decision and matching platform/source/artifact evidence.
