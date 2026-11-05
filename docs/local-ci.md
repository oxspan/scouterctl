# Local CI

Use focused Gradle tests during development. Before a PR handoff or after changing the workflow, run the existing Linux CI build with `act`:

```sh
scripts/ci-local.sh
```

The wrapper selects only the `build` job in `.github/workflows/ci-release.yml`. It runs JDK setup, Kotest, packaging and package verification, then stores uploaded artifacts under `.local/act/artifacts/`. It does not select the portable, scale or release jobs. Local runs consume no GitHub-hosted runner minutes.

## WSL setup

Start Docker Desktop with its Linux engine and enable the current distribution under Settings > Resources > WSL Integration. Confirm `docker info` works inside WSL. Install [act](https://nektosact.com/installation/); this setup was prepared with v0.2.97. The wrapper uses `act` from PATH, `ACT_BIN` when supplied, or the Git-ignored `.local/tools/act-0.2.97/act` executable.

The first run downloads runner images, actions, JDK and Gradle dependencies. `--action-offline-mode` reuses already cached actions/images on later runs; it does not promise a network-free build. `ACT_IMAGE` can override the default `ghcr.io/catthehacker/ubuntu:act-24.04` image.

The local workflow uses the current working tree instead of checking out GitHub source. GitHub Gradle caching is disabled under act, and artifacts use act's local server. The wrapper does not load `.env`, `.secrets` or `.vars`, and does not pass a GitHub token. The Docker socket is not mounted into the job container. On WSL with Docker Desktop, the artifact server binds to WSL loopback and the container reaches it through `host.docker.internal` and Windows localhost forwarding. Other environments use act's bridge-network address. If uploads fail, check this connection before rerunning the build.

## Validation boundaries

- Linux: act exercises the actual workflow build job in a Docker container. Use local Gradle directly for fast targeted tests; do not repeat both full suites for every edit.
- Windows: verify the same built package with a native Windows JDK 21. The repository's `.github/scripts/verify-portable-package.ps1` and `./gradlew.bat test -PcliJar=...` are the existing package and Kotest paths. Run them from a Windows checkout; WSL/Linux execution is not Windows validation.
- macOS: WSL and Linux containers cannot establish native macOS behavior. Use a real Mac or an explicitly requested hosted validation when it matters for release.
- Hosted behavior: act does not prove GitHub permissions, branch rules, concurrency, hosted artifact retention or release publication. Its runner image differs from GitHub-hosted images.

## Hosted execution policy

Ordinary PR events and pushes to develop no longer start this workflow. Hosted execution remains available through `workflow_dispatch`; pushes to master retain the existing release pipeline. This reduces repeated candidate/PR/merge validation while preserving release automation. Local reports and review evidence remain required before integration; a skipped or absent hosted check is not a test pass.

See [act's compatibility limits](https://nektosact.com/not_supported.html) and [artifact-server documentation](https://nektosact.com/usage/index.html#action-artifacts).
