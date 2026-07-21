ARG NODE_BASE_IMAGE=node:22-bookworm-slim@sha256:6c74791e557ce11fc957704f6d4fe134a7bc8d6f5ca4403205b2966bd488f6b3
FROM ${NODE_BASE_IMAGE}

ARG PNPM_VERSION=9.15.4

RUN apt-get update \
    && apt-get install --yes --no-install-recommends git ca-certificates \
    && npm install --global "pnpm@${PNPM_VERSION}" \
    && mkdir -p /pnpm/store \
    && chown -R node:node /pnpm \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* /root/.npm

COPY --chown=root:root preview-gateway.js /opt/ai-code-mother/preview-gateway.js

WORKDIR /workspace
USER node

ENTRYPOINT []
