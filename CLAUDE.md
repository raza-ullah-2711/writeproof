# Writeproof — notes for coding agents

Read `docs/architecture.md` before changing anything. Its **Rules** section is
binding: the wallet keypair is the root of trust, handwriting is a fuzzy biometric
unlock + liveness check, letters are immutable and stored as ciphertext only, and
the ledger is always accessed through `LedgerService`.

## Commands

- Backend (`/backend`): `./mvnw verify` — needs Docker for Testcontainers.
- Frontend (`/frontend`): `npm ci && npm test && npm run build && npm run format:check`
  — needs Node `^22.22.3` or `>=24.15`.
- Local DB: `cp .env.example .env`, set `DB_PASSWORD` (and `JWT_SECRET`, `HANDWRITING_DATA_KEY`
  for the backend), `docker compose up -d`.
- Security posture (headers, CSP, rate limits, encryption at rest): `docs/security.md`.

## Conventions

- One backlog task per PR; finish with green tests and a short PR summary.
- No secrets in the repo; read config from environment variables.
- New schema changes go in a new `backend/src/main/resources/db/migration/V<n>__*.sql`.
- Production stack and runbook: `deploy/`, `docs/deployment.md`. Keep `deploy/smoke-test.sh` passing.
