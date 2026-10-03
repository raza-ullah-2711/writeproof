# External security review: brief for testers

This is the starting point for an independent security review and penetration test of
Writeproof before launch. It says what the system promises and where it's most likely to break.
It also covers how to set up a test environment and what not to re-report. The design docs
linked below have the detail. [security.md](security.md) lists the hardening already in place.

## What we're asking for

- A **penetration test** of a staging deployment: the public app, the admin app and the API.
- A **design review** of the cryptographic protocols (wallet, sealed letters, backups,
  contacts, the ledger and its public anchoring). The browser does most of the security work, so
  bugs here matter as much as server bugs.
- A **report** with each finding's impact, reproduction steps, and a severity (CVSS 3.1 or 4.0).
  Note anything that breaks one of the [properties](#what-must-hold) below, even if hard to exploit.
- A short **retest** after fixes.

## The system in one page

Writeproof is a social network where your identity is a key pair (the "wallet") in your browser.
Your handwriting is a biometric unlock and liveness check on top of it. Letters are signed,
encrypted to the recipient, and committed to an append-only ledger, so they can never be edited
or unsent. Read [architecture.md](architecture.md), "Rules", first: those are the design
invariants.

```
Browser (public app, https://DOMAIN)      Browser (admin app, https://ADMIN_DOMAIN)
  wallet keys: non-extractable, IndexedDB    own copy of an admin's wallet (per origin)
        │  same origin, no CORS                     │
        ▼                                           ▼
Caddy (TLS, headers) ── public host: /api/** except /api/admin/**, strips X-Writeproof-Surface
                     └─ admin host: only admin API + sign-in + backup restore + status,
                        sets X-Writeproof-Surface: admin; optional IP allowlist
        │  internal network only
        ▼
Spring Boot API ── PostgreSQL (ciphertext for letters, contacts, backups; handwriting
        │          encrypted at rest with HANDWRITING_DATA_KEY)
        └──────── Rekor (rekor.sigstore.dev): every published ledger checkpoint is anchored there
```

Deployment: `deploy/compose.yml` (Postgres, API, Caddy). The API is not reachable except through
Caddy. Runbook: [deployment.md](deployment.md).

## What must hold

Treat each line as a claim to break.

1. **Nobody but the recipient can read a sealed letter**: not the server, an admin, or anyone
   with the database. Bodies are encrypted in the browser to the recipient's X25519 key
   ([letters-and-ledger.md](letters-and-ledger.md)).
2. **Nobody can sign in as you without your wallet.** Sign-in is an Ed25519 challenge-response;
   there are no passwords ([identity.md](identity.md)). Tokens are bound to the app they were
   issued for (`aud`); see [admin.md](admin.md).
3. **A letter can't be forged, altered, deleted or backdated.** It is signed by the author's
   wallet and by hand, and recorded on the ledger. There are no update/delete endpoints.
4. **The operator can't rewrite or fork the ledger unnoticed.** Merkle proofs against signed
   checkpoints, key rotation statements, and anchoring in Rekor make this detectable by
   browsers and outside witnesses ([ledger.md](ledger.md)).
5. **Handwriting is never published and is encrypted at rest.** The liveness check should reject
   replayed or synthetic strokes ([handwriting-verification.md](handwriting-verification.md)).
6. **Admins can't read private content**, can't act as users, and every admin action is audited.
   The admin app is isolated from the public app's origin ([admin.md](admin.md)).
7. **A recovery code restores a wallet; nothing else does.** The server stores only an encrypted
   blob it can't open ([backup-and-recovery.md](backup-and-recovery.md)).
8. **Contacts are private**: an encrypted book only the wallet can open ([contacts.md](contacts.md)).

## Who we worry about

- **An outsider** with a browser and the public API.
- **A registered user** attacking other users: impersonation, reading someone else's letters,
  forging replies, abusing reports or moderation.
- **A malicious or compromised server**: the operator, someone with the database, or someone with
  the API process. Properties 1, 3, 4, 7 and 8 are meant to survive this, through what the
  browser and witnesses check. Point 5 holds against a database thief, not against the API
  process, which holds the key.
- **A compromised admin or moderator account.**
- **A script on the public origin** (an XSS) trying to reach wallet keys or an admin's session.

## Where to look first

Roughly in priority order, with the questions we'd most like answered.

1. **Browser-side cryptography and key handling** (`frontend/src/app/crypto`, `wallet/`,
   `letters/`, `contacts/`).
   - Can page script, or an XSS despite CSP and Trusted Types, exfiltrate or misuse wallet keys?
   - Are nonces, HKDF inputs and associated data used correctly everywhere?
   - Can a malicious server feed the browser data that makes it decrypt to, or display, something
     forged?
2. **Authentication and session surfaces** (`backend/.../auth`).
   - Challenge replay, token audience confusion between the public and admin apps.
   - The `X-Writeproof-Surface` header: can a client set it on the public host?
   - Forced sign-out and suspension, and JWT secret rotation (`JWT_PREVIOUS_SECRET`).
3. **Authorization between users.** IDOR on letters, threads, contacts, backups (lookup ids),
   open-letter reports and calibration data. See the [endpoint inventory](#endpoint-inventory).
4. **Admin isolation.**
   - Reaching `/api/admin/**` from the public host or with a public-app token.
   - Role changes, bootstrap admins, moderator limits, and audit-log completeness.
   - The `ADMIN_ALLOWED_IPS` allowlist behind Docker's port publishing.
5. **Ledger integrity** (`backend/.../ledger`, `frontend/src/app/letters/ledger-*`, `rekor.ts`).
   - Can a server pass browser or witness checks while serving a forked or rewritten history?
   - Can it do so through key rotation statements or anchor relays, or by withholding anchors?
   - Can it exploit the 24-hour anchoring grace period in browsers?
6. **Handwriting liveness and matching.**
   - Can scripted, replayed or generated strokes pass enrolment or verification?
   - The threshold is calibrated only on synthetic data so far ([calibration.md](calibration.md)).
     A bypass is a finding, and so is a measurement.
7. **Input handling and resource limits.** Request size (4 MiB), JSON and handwriting sample
   parsing, rate limits per IP and per account, and the cost of proofs and verification.
8. **Deployment configuration.** Caddy headers and CSP, TLS, which actuator endpoints are
   exposed, Docker networking, secrets handling (`deploy/.env`, `generate-env.sh`) and backups
   (`deploy/backup.sh`).

## Out of scope

- Volumetric DoS, and anything aimed at third parties: Sigstore/Rekor, Let's Encrypt, hosting.
  Application-level resource exhaustion is in scope.
- Social engineering, physical access, and the users' own devices and browser extensions.
- The **production** deployment and its users. Test only the staging instance below.
- **Writing to the public Rekor log** (entries are permanent): keep `LEDGER_REKOR_URL` empty on
  staging, or use your own Rekor instance.

## Setting up a test environment

On a Linux host with Docker, with DNS for `staging.example` and `admin.staging.example`:

```bash
git clone https://github.com/raza-ullah-2711/writeproof && cd writeproof
./deploy/generate-env.sh staging.example            # writes deploy/.env with fresh secrets
sed -i '/^LEDGER_REKOR_URL=/d' deploy/.env          # don't anchor test data in the public log
docker compose -f deploy/compose.yml up -d --build --wait
./deploy/smoke-test.sh https://staging.example
```

- **Accounts:** create wallets in the app (Wallet → Create wallet); each browser profile is a
  user.
- **Admin:** create a wallet, copy its address, and add it to `ADMIN_PUBLIC_KEYS` in
  `deploy/.env`. Restart, create a recovery code in the public app, and restore it at
  `https://admin.staging.example`.
- **Handwriting:** enrolment and hand-signed letters need real strokes (mouse, pen or touch).
  `HANDWRITING_EXPOSE_SCORES=true` in `deploy/.env` returns similarity scores, which helps with
  liveness and matching research. It is off in production.
- **Rate limits** are on. Ask us (or set `RATE_LIMITS_ENABLED=false`) to switch them off for
  tests where they get in the way, and test them separately.
- **An outside witness:** see [ledger.md](ledger.md), "Outside witnesses". Use `--no-rekor`
  while anchoring is off.
- The repository, including all tests, is available to you. `./mvnw verify` (backend) and
  `npm test` (frontend) run everything locally.

## Known limitations

These are documented decisions or open items. Challenge them if you think they're wrong, but they
aren't new findings on their own.

- **The browser checks run in JavaScript the server serves.** A malicious operator can ship
  modified code. The checks protect against a compromised API or database behind an honest
  frontend, and they make a dishonest server detectable by people who check.
- **Trust on first use** of the ledger key in each browser ([ledger.md](ledger.md)).
- **No forward secrecy for sealed letters:** a stolen X25519 key opens past letters
  ([letters-and-ledger.md](letters-and-ledger.md)).
- **Handwriting is protected from database thieves, not from the API process**, which holds the
  key. The matching threshold isn't yet calibrated on real people.
- **Single API instance.** Rate limits are in memory, per process. That is deliberate while there
  is one server ([security.md](security.md), "Still open").
- **Account deletion keeps immutable letters.** "Close and forget" deletes everything deletable,
  but sealed letters and their metadata stay for the other party, and become unreadable to the
  deleted user ([launch-policies.md](launch-policies.md)).
- **A stolen ledger key can sign a key rotation**; witnesses comparing notes would see it
  ([ledger.md](ledger.md)).
- **Split-view detection needs at least one independent witness**, and it trusts Rekor not to
  split its own view.

## Rules of engagement

- Test only the staging hosts, during the agreed window, from the agreed source addresses (send
  them to us if `ADMIN_ALLOWED_IPS` is on).
- Treat any handwriting you capture as biometric data. Use your own or synthetic samples, never
  collect anyone else's, and delete them when the test is over.
- Stop and tell us at once if you find something that would expose real users' data or keys,
  or that undermines the ledger's guarantees.
- Report by encrypted email or a private channel we agree on. Don't put findings in public
  issues.

## Endpoint inventory

Access is enforced in `SecurityConfig`. *Public* means no token. *User* means a public-app
session. *Admin* and *moderator* mean an admin-app session with that role. All `/api/admin/**`
paths are only routed on the admin host.

| Endpoint | Access |
| --- | --- |
| `POST /api/accounts` | public |
| `POST /api/auth/challenge`, `POST /api/auth/verify` | public (admin host: admin-app token, admins and moderators only) |
| `GET /api/backups/{lookupId}` | public |
| `GET /api/system/status` | public |
| `GET /api/open-letters/{letterHash}`, `POST /api/open-letters/{letterHash}/reports` | public |
| `GET /api/ledger/key`, `/checkpoint`, `/checkpoints`, `/anchors`, `/anchor/latest`, `/proof/inclusion`, `/proof/consistency` | public |
| `GET /actuator/health` | public (the only actuator endpoint exposed) |
| `GET /api/ledger/entries`, `GET /api/ledger/verify` | user |
| `GET /api/accounts/by-key/{publicKey}` | user |
| `GET /api/me`, `GET /api/me/status`, `PUT /api/me/encryption-key` | user |
| `POST /api/me/deletion` | user, plus a fresh wallet signature ([launch-policies.md](launch-policies.md)) |
| `GET`/`PUT /api/me/backup`, `GET`/`PUT /api/me/contacts` | user |
| `GET`/`POST /api/me/open-letters`, `POST /api/me/open-letters/{letterHash}/appeal` | user |
| `POST /api/letters`, `GET /api/letters/{id}`, `/inbox`, `/sent`, `/threads`, `/threads/{threadId}` | user |
| `GET`/`POST /api/handwriting/enrolment`, `POST /api/handwriting/enrolment/deletion`, `POST /api/handwriting/verify` | user |
| `GET`/`DELETE /api/calibration`, `POST /api/calibration/consent`, `/samples`, `GET /api/calibration/forgery-target` | user |
| `GET /api/admin/me` | any admin-app session |
| `GET /api/admin/moderation/queue`, `/appeals`, `GET /api/admin/moderation/letters/{letterHash}`, `POST …/dismiss`, `…/remove`, `…/restore`, `…/uphold` | moderator or admin |
| `GET /api/admin/preserved`, `GET /api/admin/preserved/{letterHash}` (audited), `POST …/report` | admin |
| `GET /api/admin/dashboard`, `/audit`, `/accounts`, `/accounts/{id}`, `/admins`, `/system` | admin |
| `POST /api/admin/accounts/{id}/sign-out`, `/rate-limits/reset`, `POST`/`DELETE …/suspension` | admin |
| `PUT`/`DELETE /api/admin/admins/{address}`, `PATCH /api/admin/system/settings`, `POST /api/admin/system/ledger/checkpoint`, `/audit` | admin |
