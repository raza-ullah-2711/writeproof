# Wallet backup and recovery (Task 7)

Without a backup, clearing site data or losing the device loses the account and every letter.
Writeproof has no passwords, so the backup is protected by a **generated recovery code**.

## Recovery code (`crypto/recovery-code.ts`)

- 128 random bits, written as 26 Crockford base32 characters plus a 2-character checksum
  (the first 10 bits of SHA-256): `XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX`.
- Parsing ignores case, spaces and dashes, and reads O as 0 and I/L as 1. The checksum catches
  typos before any network request.
- Shown **once**, never stored or sent anywhere.

## Backing up (signed in, Home → Backup)

1. `WalletService.exportKeys()` unwraps both private keys as extractable, in memory only, and
   exports them as PKCS#8.
2. `encryptBackup` (`wallet/wallet-backup.ts`):
   `key = HKDF-SHA256(code, random 32-byte salt, "writeproof/wallet-backup/v1/key")`, then
   AES-256-GCM over `{publicKey, encryptionPublicKey, identityPkcs8, encryptionPkcs8}` with
   AAD `writeproof/wallet-backup/v1\n<address>`.
3. `PUT /api/me/backup {lookupId, blob}`, where
   `lookupId = HKDF-SHA256(code, salt "writeproof/wallet-backup/v1", ".../lookup")`. The server
   checks the blob's sizes and that its (plaintext) address is the uploader's, then stores it.
   One backup per account: a new code replaces the old one, which stops working.

Because the code has 128 bits of entropy, a fast KDF is enough. Nobody can guess a lookup id
or brute-force the ciphertext.

## Restoring (new device, Home → "Enter your recovery code")

1. Parse the code, derive `lookupId`, then `GET /api/backups/{lookupId}` (no login needed).
2. Decrypt. A wrong code or a swapped address fails AES-GCM authentication.
3. `WalletService.restore()` **proves the keys are genuine** before saving: the identity key
   signs a random challenge that must verify under the backed-up address, and the encryption
   key must agree on an X25519 secret with a fresh ephemeral key.
4. The keys are stored like a freshly created wallet (wrapped under a new non-extractable
   key), then the browser logs in. Past letters can be read again.

The same flow adds a second device: restore the code there too.

## API

| Endpoint                      | Auth  | Purpose                                   |
| ----------------------------- | ----- | ----------------------------------------- |
| `PUT /api/me/backup`          | login | Store or replace this account's backup    |
| `GET /api/me/backup`          | login | `{backedUp, backedUpAt}`                  |
| `GET /api/backups/{lookupId}` | none  | Fetch the encrypted blob (404 if unknown) |

Table `wallet_backups` (V6). Unlike letters, backups are replaceable.

## What a leaked code allows

Anyone holding the code can restore the wallet and read the account's letters. They **can't
send** letters as the owner, because sending needs a fresh handwritten signature that matches
the enrolment (Task 6). Replacing the code invalidates the old one, but it doesn't revoke a
wallet someone already restored. Key rotation is a follow-up.

## Limitations / follow-ups

- No key rotation or revocation of restored copies. One code at a time.
- No rate limit on `GET /api/backups/{id}` yet (lookup ids are 256-bit, so this is about load,
  not guessing). Folded into the hardening task.
- No offline export (backup file or printable sheet). The server copy is the only one.
- The address is stored in plaintext alongside the blob, so the server knows which account
  has a backup (it knew the account anyway).
