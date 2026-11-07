#!/usr/bin/env bash
set -euo pipefail

root=$(git rev-parse --show-toplevel)
cd "$root"
if [[ $# -ne 0 ]]; then
  echo 'Usage: scripts/ci-local.sh (configure ACT_BIN or ACT_IMAGE through the environment)' >&2
  exit 2
fi
act_bin=${ACT_BIN:-act}
if [[ -z "${ACT_BIN:-}" ]] && ! command -v "$act_bin" >/dev/null 2>&1; then
  act_bin="$root/.local/tools/act-0.2.97/act"
fi
if [[ ! -x "$act_bin" ]] && ! command -v "$act_bin" >/dev/null 2>&1; then
  echo 'Install act first; see docs/local-ci.md.' >&2
  exit 2
fi
docker_os=$(docker info --format '{{.OperatingSystem}}')
artifact_args=()
unset ACTIONS_RUNTIME_URL ACTIONS_RUNTIME_TOKEN
if [[ -n "${WSL_DISTRO_NAME:-}" && "$docker_os" == 'Docker Desktop' ]]; then
  artifact_args=(--artifact-server-addr 127.0.0.1)
  export ACTIONS_RUNTIME_URL=http://host.docker.internal:34567/
fi
mkdir -p .local/act/artifacts
exec "$act_bin" workflow_dispatch \
  --workflows .github/workflows/ci-release.yml \
  --job build \
  --platform "ubuntu-latest=${ACT_IMAGE:-ghcr.io/catthehacker/ubuntu:act-24.04}" \
  --container-daemon-socket - \
  --network bridge \
  --action-offline-mode \
  --artifact-server-path "$root/.local/act/artifacts" \
  "${artifact_args[@]}" \
  --secret-file /dev/null --env-file /dev/null --var-file /dev/null
