# Deployment

Writeproof runs as three containers (`deploy/compose.yml`):

| Service    | Image                         | Exposed         | Role                                                                             |
| ---------- | ----------------------------- | --------------- | -------------------------------------------------------------------------------- |
| `web`      | `frontend/Dockerfile` (Caddy) | 80, 443 (+ UDP) | HTTPS (automatic certificates), the app, proxies `/api/*` and `/actuator/health` |
| `backend`  | `backend/Dockerfile`          | internal only   | Spring Boot API, runs Flyway migrations on start                                 |
| `postgres` | `postgres:17-alpine`          | internal only   | Data (volume `pgdata`)                                                           |

Startup is ordered by health: Postgres, then the API (healthy once migrated and serving), then
Caddy. The images run as non-root. The API shuts down gracefully (finishes in-flight requests,
up to 20s).

## First deployment

Needs a Linux host with Docker (Compose v2), ports 80/443 open, and DNS `A`/`AAAA` records for
your domain **and** for the admin app's host (`admin.<your domain>`, `ADMIN_DOMAIN` in
`deploy/.env`) pointing at it. See [admin.md](admin.md) for why admin has its own host.

To limit the admin host to known networks, add `ADMIN_ALLOWED_IPS` to `deploy/.env`
(space-separated addresses or CIDR ranges, e.g. `ADMIN_ALLOWED_IPS=203.0.113.7/32 10.8.0.0/24`).
Everyone else gets `403` there; the public host is unaffected. Caddy must see real client
addresses for this: check by setting it to your own address and confirming the admin host still
opens for you and not from elsewhere.

```bash
git clone https://github.com/raza-ullah-2711/writeproof && cd writeproof
./deploy/generate-env.sh writeproof.example.com   # deploy/.env: domain + fresh secrets (mode 600)
docker compose -f deploy/compose.yml up -d --build --wait
./deploy/smoke-test.sh https://writeproof.example.com
```

Caddy obtains a Let's Encrypt certificate for each host on first request. To try it locally, use
`generate-env.sh localhost` (Caddy's local CA; the admin app is then on `https://admin.localhost`)
and `INSECURE=1 ./deploy/smoke-test.sh https://localhost`.

**Back up `deploy/.env` now, separately from database backups.** It holds:

| Secret                 | If lost                                                                                                                           |
| ---------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `HANDWRITING_DATA_KEY` | Every stored enrolment, signature history and calibration sample becomes unreadable (users must re-enrol; letters are unaffected) |
| `LEDGER_SIGNING_KEY`   | Every browser and witness that pinned the ledger key refuses the ledger: rotating needs the old key (see [ledger.md](ledger.md))  |
| `JWT_SECRET`           | Everyone is logged out (harmless: logging in is a signature)                                                                      |
| `DB_PASSWORD`          | Reset it in Postgres and `.env`                                                                                                   |

Letters and wallet backups are end-to-end encrypted with users' own keys. The server can't
decrypt them with or without these secrets.

## Behind an existing proxy

On a host that already runs a reverse proxy on 80/443 for other apps (e.g. Nginx Proxy Manager),
use `deploy/compose.proxied.yml` instead. It publishes no ports and builds nothing on the host:

- the proxy terminates TLS for both hosts and forwards them to `writeproof-web:80` (plain HTTP)
  over its Docker network (`PROXY_NETWORK`, default `shared`), with "force HTTPS" on;
- only `writeproof-web` joins that network; the API and the database stay on the stack's own,
  and every service and container is named `writeproof-…`, so no other app's DNS name changes;
- CPU and memory are capped per container;
- Caddy trusts `X-Forwarded-For` only from `TRUSTED_PROXIES` (default: private addresses, i.e.
  the proxy network) and reads it right to left, so a client can't fake its address to pass
  `ADMIN_ALLOWED_IPS` or rate limits (verified: a spoofed allowed address gets 403).

Build the images on another machine (a shared host's CPU belongs to its other apps), from a
clean checkout, and load them on the host:

```bash
docker build -t writeproof-backend:<version> backend && docker build -t writeproof-web:<version> frontend
docker save writeproof-backend:<version> writeproof-web:<version> | gzip > writeproof-images-<version>.tar.gz
# on the host, in the checkout:
docker load -i writeproof-images-<version>.tar.gz
echo "WRITEPROOF_VERSION=<version>" >> deploy/.env     # or edit the existing line when upgrading
docker compose -f deploy/compose.proxied.yml up -d --wait
./deploy/smoke-test.sh https://writeproof.example.com
```

Backups: `WRITEPROOF_COMPOSE=compose.proxied.yml ./deploy/backup.sh`. The restore steps below
apply with `-f deploy/compose.proxied.yml` and the services `writeproof-db` / `writeproof-api`.

## Upgrading

```bash
./deploy/backup.sh                                   # always, before migrations run
git pull
docker compose -f deploy/compose.yml up -d --build --wait
./deploy/smoke-test.sh https://writeproof.example.com
```

Upgrading a stack created before public anchoring: turn it on once,
`echo "LEDGER_REKOR_URL=https://rekor.sigstore.dev" >> deploy/.env`. Every published checkpoint is
then anchored in Sigstore's public log, and production browsers expect it: they flag letters whose
ledger isn't anchored within a day (see [ledger.md](ledger.md#anchoring-in-a-public-log)). The
server needs outbound HTTPS to rekor.sigstore.dev. Run at least one witness (`AuditLedger`)
somewhere you don't control, so a split view would be noticed.

Upgrading a stack created before the separate admin app: add its host once,
`echo "ADMIN_DOMAIN=admin.writeproof.example.com" >> deploy/.env`, and point DNS for it at the
server. Compose refuses to start the web service without it. Admins then sign in at that host
(see [admin.md](admin.md)); `/admin` on the public host now just opens the app.

Upgrading a stack created before the independent ledger (Task 10): add a ledger key once:
`echo "LEDGER_SIGNING_KEY=$(openssl rand -base64 32)" >> deploy/.env`. Change it afterwards only
by [rotating it](ledger.md#rotating-the-ledger-key); the API refuses to start with a key nobody
handed the ledger over to.

Flyway migrations run on API start and are forward-only. To roll back code, restore the backup
taken before the upgrade (below), then start the old version.

## Ledger witnesses

Each published checkpoint is logged, and appended to `checkpoints.jsonl` in the
`ledger_checkpoints` volume. To keep the operator honest, run `AuditLedger` on a schedule from
machines you don't run this stack on (see [ledger.md](ledger.md#outside-witnesses-auditledger)),
and keep their `witness.json` files.

## Backups and restore

`./deploy/backup.sh` writes `deploy/backups/writeproof-<UTC timestamp>.sql.gz` (a `pg_dump` that
recreates everything, including the append-only triggers). Schedule it, e.g. with cron:
`17 3 * * * /srv/writeproof/deploy/backup.sh`, and copy the files off the host.

Restore (also onto a fresh host, with the **same** `deploy/.env`):

```bash
docker compose -f deploy/compose.yml up -d --wait postgres
docker compose -f deploy/compose.yml stop backend
gunzip -c deploy/backups/writeproof-<timestamp>.sql.gz \
  | docker compose -f deploy/compose.yml exec -T postgres psql -q -U writeproof -d writeproof -v ON_ERROR_STOP=1
docker compose -f deploy/compose.yml up -d --wait
```

This procedure was rehearsed: back up, wipe every volume, start empty, restore. Accounts,
letters, the ledger, encrypted enrolments and the append-only triggers all came back, and the
smoke test passed.

## What the smoke test checks

`deploy/smoke-test.sh <url>` (also run by CI against a freshly built stack):

- the API is healthy through the proxy (the first request retries connection and TLS errors for
  up to a minute while Caddy issues its certificate; an HTTP error such as a 502 fails at once);
- HSTS, `frame-ancestors 'none'`, `nosniff`, `no-referrer` and `no-cache` are on the app;
- the production CSP with Trusted Types is served;
- SPA routes fall back to the app, and hashed bundles are cached immutably;
- registering an account works end to end;
- no actuator endpoint other than health is reachable;
- HTTP redirects to HTTPS;
- the public host serves no admin API and no admin code;
- the admin app is served on its own host (`admin.` + the base host, or the second argument) with
  HSTS, `noindex` and its own CSP, and that host routes only the admin API.

## Operational notes

- **Rate limits** see the real client: the API trusts `X-Forwarded-For` only from the internal
  proxy (`SERVER_FORWARD_HEADERS_STRATEGY=native`). Clients can't evade limits by sending their
  own `X-Forwarded-For`; this was verified against the running stack. The limits are
  per-process: before running more than one API instance, move them to a shared store
  (`docs/security.md`).
- **Logs:** `docker compose -f deploy/compose.yml logs -f backend web`.
- **Calibration export** (`docs/calibration.md`) runs as a one-shot container with the same
  environment. Run it as your own user, so it can write to the mounted directory (the image's
  user can't), and it exits when done:

  ```bash
  docker compose -f deploy/compose.yml run --rm --user "$(id -u):$(id -g)" -v /secure:/out backend \
    --spring.main.web-application-type=none --writeproof.calibration.export-to=/out/dataset.json
  ```

- Not included yet: image publishing to a registry, monitoring/alerting, and off-host backup
  shipping. These depend on where you host.
