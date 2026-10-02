# Wallet identity (Task 2)

An account **is** an Ed25519 public key. There are no usernames, passwords or
email. The private key is generated in the browser and never leaves it.

## Key storage (browser)

`WalletService.create()` uses WebCrypto:

1. Generate an Ed25519 keypair (extractable only so it can be wrapped once).
2. Generate an AES-GCM-256 **wrapping key** with `extractable: false`.
3. `wrapKey('pkcs8', privateKey, wrappingKey, {AES-GCM, iv})` and store in IndexedDB
   (`writeproof` → `wallet` → `primary`):
   `{ version, publicKey (raw 32 B), wrappedPrivateKey, iv, wrappingKey, createdAt }`.
4. Unwrap into a **non-extractable** signing key held in memory; drop the
   extractable copy.

On later visits `load()` unwraps the stored ciphertext into a non-extractable
signing key again. `KeyStore.saveNew` refuses to overwrite an existing wallet.

## Login protocol

```
client                                         server
  | POST /api/accounts {publicKey}              |  201 {accountId, publicKey, createdAt}
  | POST /api/auth/challenge {publicKey}        |  200 {challengeId, nonce(32 B), expiresAt}
  |  message = "writeproof/login/v1\n" + challengeId + "\n" + b64url(nonce)
  |  signature = Ed25519.sign(privateKey, message)
  | POST /api/auth/verify {challengeId, signature}
  |                                              |  consume challenge atomically (single use,
  |                                              |  unexpired), verify signature
  |                                              |  200 {token (HS256 JWT, sub=accountId), expiresAt}
  | GET /api/me  (Authorization: Bearer ...)     |  200 {accountId, publicKey, createdAt}
```

- All binary values on the wire are unpadded base64url.
- The **client builds the signed message itself** from the challenge id and
  nonce (`login-message.ts`, mirrored by `LoginMessage.java`, pinned by a shared
  test vector). The server can never get the wallet to sign arbitrary bytes, and
  the `writeproof/login/v1` domain prefix means a login signature can never be
  valid as a letter signature (Task 5 must use a different prefix).
- A challenge is marked used **before** the signature is checked, so a wrong
  signature burns it: no retries against the same nonce.
- Challenges live 2 minutes; expired ones are purged every 10 minutes.
- Tokens live 15 minutes (`JWT_TTL`) and are kept in memory only. Logging in
  again is just another signature.

## Configuration

| Variable     | Meaning                                                |
| ------------ | ------------------------------------------------------ |
| `JWT_SECRET` | Base64 HMAC key, ≥ 32 bytes. `openssl rand -base64 32` |
| `JWT_TTL`    | Optional ISO-8601 duration, default `PT15M`            |

## Known limitations / follow-ups

- **No recovery.** Clearing site data or losing the device loses the identity.
  A backup/export flow (e.g. recovery phrase that re-wraps the key) is needed
  before real users.
- **At-rest encryption scope.** The wrapping key is non-extractable, so no script
  can read the private key bytes. Same-origin script (e.g. XSS) can still _use_
  the key while the page is open, and someone with full access to the browser
  profile can run the browser to unwrap it. A strict CSP should land before
  launch. The handwriting unlock (Tasks 3–4) gates use of the key in the UI. It
  is not a cryptographic key (architecture rule 1).
- **Sealed delivery (Task 5)** needs an encryption key. Ed25519 is signing-only,
  so add an X25519 key to the wallet rather than reusing the signing key.
- No rate limiting on registration or challenge issuance yet.
- No token refresh or revocation. Tokens are short-lived instead.
