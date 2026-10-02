# Writeproof architecture

Writeproof is a social network where your handwriting is your identity. There are
no usernames, passwords or email: you unlock your account by signing by hand.
Every post ("letter") is signed and committed to an append-only ledger, so once
sent it can't be edited or unsent — like a sealed letter.

## Rules (do not violate)

1. **Root of trust is a keypair (the "wallet"), not the handwriting.** A signature
   image is not secret and varies every time, so it can never be a cryptographic
   key by itself.
2. **Handwriting is a biometric unlock on top of the key**, plus a
   liveness/authenticity check: prove a live human hand-wrote this now (not a
   bot, a replay, or AI-generated strokes).
3. **Capture stroke dynamics** (`x, y, t, pressure, penDown`), never just an image.
4. **Matching is fuzzy** (similarity score + threshold), never exact equality.
5. **Letters are immutable.** No update or delete endpoints for sent letters.
6. **Sealed delivery.** Letter bodies are encrypted to the recipient's public key;
   the server stores ciphertext only.
7. **The chain is behind an interface (`LedgerService`).** A local mock ledger
   (hash-chained table) comes first; no specific blockchain is chosen yet.

## Stack

| Area      | Choice                                                     |
| --------- | ---------------------------------------------------------- |
| Backend   | Java 21, Spring Boot 3, Maven, PostgreSQL, Flyway          |
| Frontend  | Angular (latest), standalone components, signals, zoneless |
| Local dev | docker-compose (PostgreSQL)                                |
| Tests     | JUnit 5 + Testcontainers (backend), Vitest (frontend)      |
| CI        | GitHub Actions — `.github/workflows/ci.yml`                |

## Conventions

- Every task ends with passing tests and a short summary in the PR description.
- Secrets stay out of the repo; configuration comes from environment variables
  (see `.env.example`).
- Small, reviewable PRs scoped to one task.
- Flyway migrations are append-only: never edit an applied `V*__*.sql`.

## Backlog

1. **Scaffold** — Spring Boot app (health endpoint, Flyway, Postgres config),
   Angular app, docker-compose for Postgres, CI building and testing both. ✅
2. **Wallet identity** — Ed25519 keypair generated client-side; register with
   public key; login via challenge-response (server issues nonce, client signs,
   server verifies, issues JWT). Private key never leaves the browser
   (encrypted in IndexedDB). ✅ — see [identity.md](identity.md).
3. **Handwriting capture** — Angular canvas component using Pointer Events that
   records strokes as `{x, y, t, pressure, penDown}` arrays, with replay preview
   and JSON export. ✅ — see [handwriting-capture.md](handwriting-capture.md).
4. **Handwriting verification v1** — enrolment (3–5 samples) and verification
   using feature extraction + Dynamic Time Warping, returning a similarity
   score. Basic liveness heuristics (timing variance, unnatural constant
   velocity). Unit tests with synthetic strokes. ✅ — see
   [handwriting-verification.md](handwriting-verification.md).
5. **Letters + mock ledger** — compose a letter, sign its hash with the wallet
   key, encrypt the body to the recipient's public key, append to
   `LedgerService` (mock: hash-chained table). Recipient can decrypt and verify
   signature and chain integrity. ✅ — see [letters-and-ledger.md](letters-and-ledger.md).
6. **Hand-signed letters** — sending requires writing your signature: it is verified against
   your enrolment (match, liveness, freshness, not a replay of a recent signature), its hash is
   committed to by the wallet-signed letter header, and the strokes travel sealed inside the
   letter so the recipient can replay them. ✅ — see [letters-and-ledger.md](letters-and-ledger.md).
7. **Wallet backup & recovery** — recovery code (128-bit, checksummed), encrypted backup stored
   server-side as ciphertext, restore on a new device with proof the keys match the address,
   second device via the same code. ✅ — see [backup-and-recovery.md](backup-and-recovery.md).
8. **Security hardening** — API security headers and a production CSP with Trusted Types,
   rate limits, 4 MiB body cap, handwriting encrypted at rest (with a Java migration for
   existing rows), deletion that requires a fresh verified signature, scores hidden by
   default. ✅ — see [security.md](security.md).
9. **Real-data calibration** — opt-in sample collection, FAR/FRR evaluation harness, retuned
   thresholds, per-device templates.
10. **Independent ledger** — anchor signed checkpoints externally or pick a chain; Merkle
    inclusion proofs instead of re-walking from genesis.
11. **Social layer** — contacts (petnames, QR address exchange), threads, open (unsealed but
    signed) letters. Needs product decisions.
