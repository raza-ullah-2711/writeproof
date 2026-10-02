# Writeproof

_Every word, provably human._

A social network where your handwriting is your identity. No usernames,
passwords or email: you unlock your account by signing by hand. Every letter is
signed and committed to an append-only ledger — once sent, it can't be edited or
unsent.

See [`docs/architecture.md`](docs/architecture.md) for the design rules and backlog,
and the per-feature docs: [identity](docs/identity.md),
[handwriting capture](docs/handwriting-capture.md),
[handwriting verification](docs/handwriting-verification.md),
[letters and ledger](docs/letters-and-ledger.md), [independent ledger](docs/ledger.md), [contacts](docs/contacts.md), [threads](docs/threads.md), [open letters](docs/open-letters.md),
[backup and recovery](docs/backup-and-recovery.md), [security](docs/security.md),
[calibration](docs/calibration.md).

## Layout

| Path        | What                                         |
| ----------- | -------------------------------------------- |
| `backend/`  | Spring Boot 3 API (Java 21, Maven, Flyway)   |
| `frontend/` | Angular app (standalone components, signals) |
| `docs/`     | Architecture and design notes                |

## Running locally

```bash
cp .env.example .env            # then set DB_PASSWORD and the keys it lists
docker compose up -d            # PostgreSQL on :5432

# Backend on :8080 (reads DB_* and the keys from the environment)
cd backend
set -a; . ../.env; set +a
./mvnw spring-boot:run          # health: http://localhost:8080/actuator/health

# Frontend on :4200 (proxies /api and /actuator to the backend)
cd ../frontend
npm ci
npm start
```

## Deploying

`deploy/compose.yml` runs the production stack (Postgres, the API, and Caddy with automatic
HTTPS). See [`docs/deployment.md`](docs/deployment.md):

```bash
./deploy/generate-env.sh writeproof.example.com
docker compose -f deploy/compose.yml up -d --build --wait
```

## Tests

```bash
cd backend && ./mvnw verify     # requires Docker (Testcontainers PostgreSQL)
cd frontend && npm test
```

CI (`.github/workflows/ci.yml`) runs both on every push to `main` and every PR, and builds
the production images and smoke-tests the full stack over HTTPS.
