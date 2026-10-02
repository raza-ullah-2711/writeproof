# Letters and the mock ledger (Task 5)

A letter is **sealed** (only the recipient and sender can read it), **signed** by the
sender's wallet, and **recorded** on an append-only, hash-chained ledger. Once sent it can't
be edited or unsent. The recipient's browser checks all three itself. Try it at `/letters`.

## Keys

| Key                | Algorithm | Purpose                                            |
| ------------------ | --------- | -------------------------------------------------- |
| Identity (address) | Ed25519   | Login, signing letters, vouching for the key below |
| Encryption         | X25519    | Receiving sealed letters                           |

Ed25519 can only sign, so each wallet also holds an X25519 key. It's wrapped and stored like
the identity key, and added automatically to wallets created before this task. At login the
browser registers it with `PUT /api/me/encryption-key`, **signed by the identity key** over
`writeproof/encryption-key/v1\n<identity>\n<encryption key>`. Senders verify that signature
before encrypting, so the server can't substitute a key of its own and read letters.

## Sending (hand-signed, v2)

Every letter is signed twice: by the sender's wallet and by their hand.

1. Look up the recipient by address: `GET /api/accounts/by-key/{address}`. Check that the
   returned identity key is the address asked for, and that the encryption key signature verifies.
2. The sender **writes their signature** on the compose pad. Its exact JSON
   (`writeproof.handwriting` v1) is the `handwriting` string. `handwritingHash = SHA-256(handwriting)`.
3. **Seal** (`letter-crypto.ts`):
   - `header = "writeproof/letter/v2\n" sender "\n" recipient "\n" sentAt "\n" handwritingHash`.
   - Plaintext `{"body": "...", "handwriting": "<the JSON>"}`: the strokes travel **sealed inside**
     the letter. AES-256-GCM under a random content key, AAD = header.
   - The content key is wrapped twice, for the recipient and for the sender (so they can
     re-read sent letters). Each wrap uses a fresh ephemeral X25519 key, then
     HKDF-SHA256(salt = ephemeral ‖ reader key, info `writeproof/letter-key/v1`), then
     AES-256-GCM with AAD = header.
4. **Hash**: `letterHash = SHA-256(header ‖ every envelope field)`, joined with `\n`.
5. **Sign**: Ed25519 over `writeproof/letter-signature/v1\n<letterHash>`. This is
   domain-separated from login messages. The wallet signature therefore commits to the
   exact handwriting.
6. `POST /api/letters` with `handwriting` alongside the envelope. The server checks sizes,
   that `sentAt` is within 5 minutes, both parties' encryption keys, and the wallet signature.
   **Only then** does it check the handwriting, so only the key holder can probe scores. The
   handwriting must:
   - verify against the sender's enrolment (Task 4: match ≥ threshold, all liveness checks);
   - have been captured within 10 minutes (`STALE` otherwise);
   - not be near-identical to an enrolled sample or one of the sender's **20 most recent letter
     signatures** (`REPLAY`). Whoever holds the device can decrypt sent letters and lift
     their strokes, so this matters;
   - not have sealed another letter already (exact hash, 409).

   A rejection is a 422 with `score`, `threshold`, `match` and `livenessFlags`. On success, one
   transaction appends `letterHash` to the ledger, stores the letter with `handwriting_hash`
   and `handwriting_score` (never the strokes), and records the sample in `handwriting_history`.

Letters sent before hand-signing (v1: header `writeproof/letter/v1` without the handwriting
hash) are still readable and verifiable. New letters must be v2.

## Reading and verifying (recipient or sender)

`LettersService.open` trusts nothing from the server except the handwriting score:

- **Signed by hand** (v2): the sealed `handwriting` hashes to the `handwritingHash` the sender's
  wallet signed, so these are exactly the strokes the server verified. The signature is shown
  and can be replayed. The similarity score itself is the server's measurement, labelled as
  such ("verified by Writeproof at sending"). v1 letters say "sent before hand-signing".
- **Signature**: recompute `letterHash` from the header and envelope, then verify the sender's
  signature over it.
- **Sealed for you, unaltered**: decrypt with this wallet's X25519 key. AES-GCM fails if the
  ciphertext, wrapped key or header was changed.
- **Ledger**: fetch entries `1..seq`, re-walk the chain from genesis (sequence, links,
  hashes), and check that the letter's entry commits to the recomputed `letterHash`.

## Ledger (`LedgerService`)

The interface is `append`, `entry`, `entries`, `head` and `verify`. `MockLedgerService` is a
Postgres table:

```
entry_hash = SHA-256("writeproof/ledger/v1\n" seq "\n" prevHash "\n" payloadHash "\n" recordedAtMillis)
prevHash(1) = 32 zero bytes
```

- `append` must join the caller's transaction. A letter and its entry commit together or not
  at all.
- Appends are serialized with `pg_advisory_xact_lock`, so concurrent writers can't fork the
  chain. The test with 40 concurrent appends fails with duplicate `seq` without the lock.
- `GET /api/ledger/entries?from&limit` (max 1000), `GET /api/ledger/verify`.

## Immutability

- No update or delete endpoints for letters. `PUT`/`PATCH`/`DELETE` return 405.
- Database triggers raise `... is append-only` on `UPDATE`, `DELETE` and `TRUNCATE` of
  `letters` and `ledger_entries` (migration V4), so a bug or a stray query can't rewrite
  history either.

## Shared formats

Header, letter hash, signed message, encryption-key binding and ledger entry hash are pinned by
the same vectors in `backend/.../letters/FormatVectorsTest.java` and
`frontend/src/app/letters/letter-format.spec.ts` / `ledger-verify.spec.ts`.

## Known limitations / follow-ups

- **The mock ledger is run by the same server.** Clients verify the chain's internal
  consistency, but the server could rewrite the whole chain consistently. A real chain (or
  publishing signed heads somewhere independent) is what makes history tamper-evident against
  the operator.
- **Verification cost grows with the ledger.** The client re-walks from genesis. Swap in
  Merkle proofs / checkpoints before the ledger is large.
- **"Signed by hand" rests on the server's verification.** The recipient can check which
  strokes signed the letter, but whether they matched the sender's enrolment is the server's
  measurement (it holds the template). A compromised server could accept a forgery.
- **Handwriting reaches the server in plaintext** at sending (to be verified). It's stored only
  as a hash on the letter and in the bounded, deletable `handwriting_history`.
- **The handwriting checks inherit Task 4's limits**: calibrated on synthetic data, and
  heuristic liveness. A rejected send also reveals a score to the key holder (no attempt limit yet).
- **Metadata is visible to the server**: who wrote to whom, when, and the ciphertext length.
- **No forward secrecy for the recipient.** A compromised X25519 key opens all past letters
  to it. There's no key rotation yet (re-registering a different key is refused).
- **No recovery.** Losing the wallet loses the ability to read past letters.
