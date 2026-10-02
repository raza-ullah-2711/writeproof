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

## Sending

1. Look up the recipient by address: `GET /api/accounts/by-key/{address}`. Check that the
   returned identity key is the address asked for, and that the encryption key signature verifies.
2. **Seal** (`letter-crypto.ts`):
   - `header = "writeproof/letter/v1\n" sender "\n" recipient "\n" sentAt` (ISO-8601 UTC, ms).
   - Body `{"body": "..."}` → AES-256-GCM under a random content key, AAD = header.
   - The content key is wrapped twice, for the recipient and for the sender (so they can
     re-read sent letters). Each wrap uses a fresh ephemeral X25519 key, then
     HKDF-SHA256(salt = ephemeral ‖ reader key, info `writeproof/letter-key/v1`), then
     AES-256-GCM with AAD = header.
3. **Hash**: `letterHash = SHA-256(header ‖ every envelope field)`, joined with `\n`.
4. **Sign**: Ed25519 over `writeproof/letter-signature/v1\n<letterHash>`. This is
   domain-separated from login messages.
5. `POST /api/letters`. The server checks sizes, that `sentAt` is within 5 minutes, that both
   parties have encryption keys, and the signature (it can verify, never decrypt). Then, in one
   transaction, it appends `letterHash` to the ledger and stores the letter.

## Reading and verifying (recipient or sender)

`LettersService.open` trusts nothing from the server:

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
- **Not yet signed by hand.** Letters are signed by the wallet only. The product thesis needs a
  fresh handwriting verification (Task 4) bound to `letterHash` before a letter is accepted.
- **Metadata is visible to the server**: who wrote to whom, when, and the ciphertext length.
- **No forward secrecy for the recipient.** A compromised X25519 key opens all past letters
  to it. There's no key rotation yet (re-registering a different key is refused).
- **No recovery.** Losing the wallet loses the ability to read past letters.
