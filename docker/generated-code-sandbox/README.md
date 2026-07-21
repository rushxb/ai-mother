# Generated-code sandbox image

Build the pinned runtime image before enabling `GENERATED_CODE_SANDBOX_MODE=container`:

```bash
docker build -t ai-code-mother/sandbox-node:1 docker/generated-code-sandbox
docker network create --driver bridge --internal ai-code-sandbox-internal
docker network create --driver bridge ai-code-sandbox-preview-gateway
docker volume create ai-code-mother-pnpm-store-v9
```

The application verifies the image during startup. Every workload receives only its generated
workspace at `/workspace`; the container runs without Linux capabilities, with
`no-new-privileges`, a read-only root filesystem, bounded memory/CPU/PIDs, and no network except
for dependency installation.
Dev Servers remain attached only to the internal Docker network. A separate, platform-controlled,
read-only gateway container joins the internal network and the dedicated preview-gateway network,
then publishes only the assigned port on host `127.0.0.1`. Generated code never joins the gateway
or dependency-egress networks.

Production must configure the sandbox image by digest (for example
`registry.example.com/ai-code/sandbox@sha256:...`) and must use a dedicated dependency-egress
network rather than Docker's default `bridge`. Provision that egress network with the platform's
firewall/proxy policy; do not attach application databases, Redis, Docker socket, or control-plane
services to either sandbox network. The mounted workspace must be writable by the configured
container UID/GID (default `1000:1000`).

Production also requires a pre-created node-local pnpm store volume. The volume is mounted writable
only when the managed command is exactly `pnpm install` with dependency-egress capability; build,
test, tool, and Dev Server containers cannot see it. Cached packages are copied into the project
instead of hard-linked, and pnpm store-integrity verification remains enabled. Treat this volume as
disposable cache rather than application state, bind its name to the sandbox image/pnpm major, set a
node disk-watermark alert, and prune or rotate it only in a maintenance window with no active
installs.

## Runtime security verification

Run the executable Docker integration contract before releasing the backend or sandbox image:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-generated-code-sandbox.ps1
```

```bash
./scripts/verify-generated-code-sandbox.sh
```

The contract verifies the real container state rather than only the generated Docker command:
non-root UID, read-only root, dropped capabilities, `no-new-privileges`, no inherited host secret,
no default route for offline workloads, cgroup resource limits, capability-scoped mounts,
loopback-only gateway publishing, Dev Server internal-network isolation, and cleanup of both
containers.
