# Security hardening (Task 8)

## Headers

**API** (Spring Security, every response): `Content-Security-Policy: default-src 'none';
frame-ancestors 'none'; base-uri 'none'; form-action 'none'`, `X-Content-Type-Options: nosniff`,
`X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Cross-Origin-Resource-Policy:
same-origin`, `Cache-Control: no-store`. `Strict-Transport-Security` (1 year, subdomains) is
added on HTTPS requests.

**Frontend** (production build only, `src/index.prod.html`): a CSP meta tag:

```
default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:;
font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self';
require-trusted-types-for 'script'; trusted-types angular angular#bundler
```

- **Trusted Types** make DOM script sinks (`innerHTML`, script URLs) refuse raw strings. That
  is the usual path from an XSS bug to running code next to the wallet's (non-extractable, but
  usable) keys. Angular's own policies are the only ones allowed.
- No `'unsafe-eval'`. `style-src 'unsafe-inline'` is needed for Angular component styles.
- Checked in Chromium against the production bundle: the whole app (wallet, backup, enrolment,
  hand-signed send, open and verify, replay, JSON export) runs with **zero violations**. From
  page code, `eval`, `new Function`, assigning a script URL and raw `innerHTML` are all blocked.
- The dev server (`ng serve`) uses the plain `index.html`, since Vite's HMR needs websockets
  and eval.

**Host requirements** (whatever serves the frontend in production): send
`Content-Security-Policy: frame-ancestors 'none'` (can't be set by meta),
`X-Content-Type-Options: nosniff` and HSTS, and serve the API on the same origin (there is no CORS).

## Rate limits

In-memory token buckets (`security/`), refilled continuously. A limited request gets `429` with
`Retry-After` and a problem-details body.

| Endpoint                                   | Limit        | Per     |
| ------------------------------------------ | ------------ | ------- |
| `POST /api/accounts`                       | 10 / hour    | IP      |
| `POST /api/auth/challenge`, `/verify`      | 30 / 10 min  | IP      |
| `POST /api/handwriting/verify`             | 20 / hour    | account |
| `POST /api/handwriting/enrolment`          | 10 / hour    | account |
| `POST /api/handwriting/enrolment/deletion` | 5 / hour     | account |
| `POST /api/letters`                        | 30 / hour    | account |
| `POST /api/me/open-letters`                | 10 / hour    | account |
| `GET /api/open-letters/{hash}`             | 600 / 10 min | IP      |
| `GET /api/backups/{id}`                    | 20 / hour    | IP      |
| `PUT /api/me/backup`                       | 10 / hour    | account |
| `PUT /api/me/contacts`                     | 120 / hour   | account |
| `POST /api/calibration/samples`            | 60 / hour    | account |
| `GET /api/calibration/forgery-target`      | 60 / hour    | account |
| `GET /api/ledger/**` (key, proofs)         | 600 / 10 min | IP      |

`RATE_LIMITS_ENABLED` (default `true`). The limits are per process. Behind a reverse proxy, set
`server.forward-headers-strategy` so per-IP limits see the real client. With more than one
instance, move the buckets to a shared store.

## Request size

Bodies over 4 MiB get `413` (declared lengths up front, chunked bodies while reading). The
largest legitimate request, an enrolment of 5 × 5,000 points, fits comfortably.

## Handwriting data at rest

Enrolment samples and the recent-signature history are stored **encrypted** (AES-256-GCM,
`BiometricCipher`) under `HANDWRITING_DATA_KEY`. The associated data names the table and account,
so ciphertext can't be moved between rows. Format `0x01 || nonce || ciphertext` (the version
byte leaves room for key rotation). Migration `V7__EncryptBiometricData` (a Java migration,
since the key lives in the app) encrypted existing rows in place and dropped the plaintext columns.

This protects database dumps and backups. It doesn't protect against a compromised application
server, which holds the key.

## Deleting handwriting

`POST /api/handwriting/enrolment/deletion {sample}` erases the enrolment and the signature
history, after which the user can enrol again. It requires a **fresh signature that verifies**
(match, liveness, freshness, not a replay). Otherwise anyone holding a stolen recovery code could
delete the owner's enrolment, enrol their own hand, and send letters as them. Letters already
sent keep their hash and score.

## Scores

`HANDWRITING_EXPOSE_SCORES` (default `false`): `/api/handwriting/verify` and rejected
sends and deletions **omit similarity scores**, since a score tells a forger how close each
attempt got. Liveness flags are still returned, because they help a genuine writer. Recipients
still see the score a letter was accepted with.

## Admin access

Admins sign in with their wallet; the role (ADMIN or MODERATOR) comes from `ADMIN_PUBLIC_KEYS` or
`admin_roles` and is checked on every request, so revocation is immediate. Admins can't read
sealed letters, contacts or handwriting, and every admin action goes to the append-only
`admin_audit_log`. A forced sign-out rejects every token issued before it on the next request;
the app forgets a token the server rejects (401). See [admin.md](admin.md).

## Configuration

| Variable                    | Required | Purpose                                        |
| --------------------------- | -------- | ---------------------------------------------- |
| `HANDWRITING_DATA_KEY`      | yes      | base64 AES-256 key for handwriting at rest     |
| `ADMIN_PUBLIC_KEYS`         | no       | wallet addresses that are always admins        |
| `LEDGER_SIGNING_KEY`        | yes      | base64 Ed25519 seed signing ledger checkpoints |
| `HANDWRITING_EXPOSE_SCORES` | no       | `true` to return scores (development only)     |
| `RATE_LIMITS_ENABLED`       | no       | `false` to disable limits (development only)   |

## Still open

- Key rotation for `HANDWRITING_DATA_KEY` and `JWT_SECRET` (formats allow it; no tooling yet).
- Shared rate-limit store for multiple instances, and per-IP limits behind proxies.
- Account deletion as a whole (letters are immutable by design; what deletion means for them
  needs a product decision).
- An external security review / penetration test before launch.
