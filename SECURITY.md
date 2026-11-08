# Security policy

English | [한국어](SECURITY.ko.md)

## Reporting a vulnerability

Please use [GitHub private vulnerability reporting](https://github.com/oxspan/scouterctl/security/advisories/new) when **Report a vulnerability** is available in this repository's Security tab. Reports should include the affected scouterctl and Collector versions, operating system and Java version, reproduction steps, and expected impact.

If the private reporting option is unavailable, open an issue asking the maintainer to enable it, without describing the vulnerability. Do not put exploit details, credentials, production captures, SQL parameters, or personal data in public issues or pull requests. Wait for a private reporting channel before sharing sensitive details.

## Supported versions

Security fixes target the latest published version; older versions do not have a separate maintenance commitment. Include the exact version and commit, if known, so the issue can be reproduced.

## Operating boundaries

- Use Collector TCP only over a trusted network or a separately secured tunnel. The CLI does not add native TLS to the Collector protocol.
- Read-only requests do not establish server-side authorization guarantees. Use an appropriate Collector account and restrict network access.
- Capture files and optional dictionary text can contain sensitive application data. Control access to exported files and redact them before sharing.
- Verify the downloaded ZIP against the checksum from the same release. This detects mismatched bytes; it does not establish the publisher's identity independently of GitHub.
