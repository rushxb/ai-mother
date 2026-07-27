#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${GENERATED_CODE_SANDBOX_E2E_IMAGE:-ai-code-mother/sandbox-node:1}"
GO_BASE_IMAGE="${GENERATED_CODE_SANDBOX_GO_BASE_IMAGE:-}"
DEPENDENCY_NETWORK="${GENERATED_CODE_SANDBOX_E2E_DEPENDENCY_NETWORK:-ai-code-sandbox-egress}"
DEV_SERVER_NETWORK="${GENERATED_CODE_SANDBOX_E2E_DEV_SERVER_NETWORK:-ai-code-sandbox-internal}"
PREVIEW_GATEWAY_NETWORK="${GENERATED_CODE_SANDBOX_E2E_PREVIEW_GATEWAY_NETWORK:-ai-code-sandbox-preview-gateway}"
PNPM_STORE_VOLUME="${GENERATED_CODE_SANDBOX_E2E_PNPM_STORE_VOLUME:-ai-code-mother-pnpm-store-v9}"

ensure_network() {
  local name="$1"
  local internal="$2"
  if ! docker network inspect "$name" >/dev/null 2>&1; then
    if [[ "$internal" == "true" ]]; then
      docker network create --driver bridge --internal "$name" >/dev/null
    else
      docker network create --driver bridge "$name" >/dev/null
    fi
  fi
  local actual
  actual="$(docker network inspect --format '{{.Internal}}' "$name")"
  [[ "$actual" == "$internal" ]] || {
    echo "Docker network '$name' internal=$actual, expected internal=$internal" >&2
    exit 1
  }
}

ensure_volume() {
  local name="$1"
  if ! docker volume inspect "$name" >/dev/null 2>&1; then
    docker volume create "$name" >/dev/null
  fi
}

cd "$PROJECT_ROOT"
docker version >/dev/null
if [[ ! "$GO_BASE_IMAGE" =~ @sha256:[0-9a-fA-F]{64}$ ]]; then
  echo "必须通过 GENERATED_CODE_SANDBOX_GO_BASE_IMAGE 提供带 sha256 摘要的 Go 基础镜像" >&2
  exit 1
fi
docker build \
  --pull \
  --build-arg "GO_BASE_IMAGE=$GO_BASE_IMAGE" \
  --file docker/generated-code-sandbox/Dockerfile \
  --tag "$IMAGE" \
  .
ensure_network "$DEPENDENCY_NETWORK" false
ensure_network "$DEV_SERVER_NETWORK" true
ensure_network "$PREVIEW_GATEWAY_NETWORK" false
ensure_volume "$PNPM_STORE_VOLUME"

export SANDBOX_E2E_HOST_SECRET="must-not-enter-generated-code-container"
./mvnw \
  -Pintegration-test \
  -Dtest=ContainerGeneratedCodeSandboxIntegrationTest \
  -DgeneratedCodeSandboxE2e=true \
  -DgeneratedCodeSandboxImage="$IMAGE" \
  -DgeneratedCodeSandboxDependencyNetwork="$DEPENDENCY_NETWORK" \
  -DgeneratedCodeSandboxDevServerNetwork="$DEV_SERVER_NETWORK" \
  -DgeneratedCodeSandboxPreviewGatewayNetwork="$PREVIEW_GATEWAY_NETWORK" \
  -DgeneratedCodeSandboxPnpmStoreVolume="$PNPM_STORE_VOLUME" \
  test
