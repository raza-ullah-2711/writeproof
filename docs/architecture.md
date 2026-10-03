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
   the server stores ciphertext only. (Open letters, Task 11c, are a separate kind that
   the author explicitly publishes in the clear. They never weaken a sealed letter: an
   addressed letter is always sealed, and nothing converts one kind into the other.)
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
9. **Real-data calibration** — opt-in collection using made-up practice names (never real
   signatures), encrypted and withdrawable; operator export; offline evaluator with
   confidence-bounded threshold recommendations. Tooling ✅; real data and retuning pending —
   see [calibration.md](calibration.md).
10. **Independent ledger** — RFC 9162 Merkle tree over the ledger, checkpoints signed by a
    dedicated ledger key and published, O(log n) inclusion and consistency proofs checked in the
    browser against a pinned key, and an `AuditLedger` command for outside witnesses. ✅ — see
    [ledger.md](ledger.md). Anchoring on a public chain and witness gossip are follow-ups.
11. **Social layer** — contacts (petnames, QR address exchange), threads, open (unsealed but
    signed) letters. Decided: address exchange only (no directory), contact book encrypted with
    the wallet and stored as ciphertext, open letters readable by anyone with the link.
    11a **Contacts** ✅ — see [contacts.md](contacts.md). 11b **Threads** ✅ — signed replies
    and conversations, see [threads.md](threads.md). 11c **Open letters** ✅ — public,
    signed and on the ledger, readable by link; see [open-letters.md](open-letters.md).
12. **Deployment packaging** — production images (non-root, layered), Caddy with automatic HTTPS
    and the required headers, health-ordered Compose stack, secrets generator, backup/restore,
    smoke test run in CI. ✅ — see [deployment.md](deployment.md).
13. **Admin side** — decided: admins sign in with their wallet (allow-listed by address); takedowns
    remove an open letter's text but keep its hash on the ledger; suspension blocks sending only;
    roles Admin and Moderator. Admins never see letter content, contacts or handwriting (they are
    encrypted) and can't rewrite letters or the ledger. 13a **Foundation + dashboard** ✅ — see
    [admin.md](admin.md). 13b **Accounts** ✅ — search, suspend/reinstate, forced sign-out,
    clear rate limits. 13c **Moderation** ✅ — reader reports, a review queue, takedowns that
    delete the text and keep the record. 13d **System controls** ✅ — registration, sending and
    publishing switches, an announcement, checkpoint and ledger-audit tools. 13e admin management
    to follow.
