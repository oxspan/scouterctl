# Security policy

English | [한국어](SECURITY.ko.md)

## Reporting a vulnerability

Please use [GitHub private vulnerability reporting](https://github.com/oxspan/scouterctl/security/advisories/new) when **Report a vulnerability** is available in this repository's Security tab. Reports should include the affected scouterctl and Collector versions, operating system and Java version, reproduction steps, and expected impact.

If the private reporting option is unavailable, open an issue asking the maintainer to enable it, without describing the vulnerability. Do not put exploit details, credentials, production captures, SQL parameters, or personal data in public issues or pull requests. Wait for a private reporting channel before sharing sensitive details.

## Supported versions

Security fixes target the latest published version; older versions do not have a separate maintenance commitment. Include the exact version and commit, if known, so the issue can be reproduced.

## Repository controls

- Secret scanning and push protection detect supported credential patterns. They do not replace review of captures, SQL text, or other sensitive data before publication.
- Changes to `master` and `develop` require a pull request, resolved review conversations, an up-to-date branch, and the GitHub Actions checks `Build and test` and `Windows command package`. Windows checks run for PRs into `master` and are intentionally skipped for PRs into `develop`. No ruleset bypass is configured. The required approval count is zero while the project has one maintainer.
- Dependabot monitors GitHub Actions and Gradle version updates on `develop`. Security updates target the default branch, `master`. The dependency submission workflow resolves and submits Gradle dependencies from `master` after pushes, weekly, or by manual dispatch on `master`. The vendored Scouter JAR needs separate provenance, checksum, and vulnerability review when it changes.
- CodeQL default setup scans GitHub Actions, Java/Kotlin, and C/C++ with local and remote input sources included. Findings need maintainer triage.
- Actions must use full commit SHAs. Allowed actions are GitHub-owned actions, `gradle/actions/*`, and `googleapis/release-please-action`. The default workflow token is read-only; individual jobs request additional permissions when needed.

## Operating boundaries

- Use Collector TCP only over a trusted network or a separately secured tunnel. The CLI does not add native TLS to the Collector protocol.
- Read-only requests do not establish server-side authorization guarantees. Use an appropriate Collector account and restrict network access.
- Capture files and optional dictionary text can contain sensitive application data. Control access to exported files and redact them before sharing.
- Verify the downloaded ZIP against the checksum from the same release. This detects mismatched bytes; it does not establish the publisher's identity independently of GitHub.
