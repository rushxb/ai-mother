ARG GO_BASE_IMAGE
ARG NODE_BASE_IMAGE=node:22-bookworm-slim@sha256:6c74791e557ce11fc957704f6d4fe134a7bc8d6f5ca4403205b2966bd488f6b3

FROM ${GO_BASE_IMAGE} AS go-template-verifier

ENV CGO_ENABLED=0 \
    GOENV=off \
    GOTELEMETRY=off \
    GOTOOLCHAIN=local \
    GOWORK=off

WORKDIR /opt/backend-template
COPY src/main/resources/project-templates/go-sqlite-backend-basic/ ./
RUN go mod download \
    && go mod verify \
    && go test -mod=readonly -count=1 -trimpath -buildvcs=false ./...

FROM ${NODE_BASE_IMAGE}

ARG PNPM_VERSION=9.15.4

ENV GOMODCACHE=/opt/go/pkg/mod \
    PATH=/usr/local/go/bin:${PATH}

RUN apt-get update \
    && apt-get install --yes --no-install-recommends git ca-certificates \
    && npm install --global "pnpm@${PNPM_VERSION}" \
    && mkdir -p /pnpm/store /opt/go/pkg \
    && chown -R node:node /pnpm \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* /root/.npm

COPY --from=go-template-verifier /usr/local/go /usr/local/go
COPY --from=go-template-verifier /go/pkg/mod /opt/go/pkg/mod
RUN chmod -R a-w /opt/go/pkg/mod

COPY --chown=root:root docker/generated-code-sandbox/preview-gateway.js /opt/ai-code-mother/preview-gateway.js

WORKDIR /workspace
USER node

ENTRYPOINT []
