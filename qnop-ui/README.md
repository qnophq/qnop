# qnop-ui

The qnop web frontend: a Vite + React 19 + TypeScript + MaterialUI SPA consuming the qnop REST API through a generated `typescript-axios` client (OpenAPI-first, ADR-0021).

## Prerequisites

- Node 24 + **pnpm**
- A JDK on the PATH — `pnpm generate:api` runs the OpenAPI generator (a Java tool) against `../qnop-api/src/main/resources/openapi/openapi.yaml`
- A running backend (`./gradlew :qnop-app:bootRun` from the repo root) for `pnpm dev`

## Commands

```bash
pnpm install
pnpm dev            # generate:api + Vite dev server (proxies /api to :8080)
pnpm build          # generate:api + tsc -b + vite build
pnpm typecheck      # generate:api + tsc -b --noEmit
pnpm test           # vitest (watch) · pnpm test:run for one-shot
pnpm lint           # eslint
pnpm format         # prettier --write · pnpm format:check
pnpm generate:api   # regenerate the typed API client
```

Conventions, architecture and decisions live in the repo root: [`../README.md`](../README.md), [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md), [`../docs/adr/`](../docs/adr/README.md).
