# Open letters (Task 11c)

The last slice of the social layer. An open letter is signed by your wallet and your hand and
recorded on the ledger, like any letter, but it is **not sealed**. Anyone with its link can read
it and check that you wrote it, without an account. As decided, there is no feed and no profile
page: people see an open letter only if its link is shared.

## Format

```
bodyHash   = SHA-256(UTF-8 body)
letterHash = SHA-256("writeproof/open-letter/v1\n" author "\n" sentAt "\n" handwritingHash "\n" b64url(bodyHash))
signed     = "writeproof/letter-signature/v1\n" b64url(letterHash)     (same message as sealed letters)
```

- The distinct domain means an open letter's hash can never equal a sealed letter's.
- `letterHash` goes on the ledger, and the link is `/open/<letterHash>`.
- `OpenLetterHashing` and `open-letter-format.ts` share a test vector.

## What's public and what isn't

- **Public:**
  - the body, exactly as signed (shown as plain text, never HTML);
  - the author's address;
  - the time;
  - the wallet signature;
  - the handwriting hash;
  - the similarity score Writeproof measured;
  - the ledger entry.
- **Not public: the handwritten strokes.** Publishing exact signature strokes would give forgers
  training material. As with sealed letters, the server verifies the strokes (match, liveness,
  freshness, not a replay), keeps only their hash, and records the signature in the bounded
  replay history. Readers therefore rely on Writeproof for "signed by hand". They can check that
  the author's wallet committed to that handwriting hash, but not the match itself.
- The author's account id is never exposed; only their public key.
- One signature seals one letter: a handwriting hash used on a sealed letter can't be reused on
  an open one, and vice versa.

## Immutability

`open_letters` (migration V12) has the same append-only triggers as letters and the ledger.
There is no edit or delete endpoint, and the publish form makes you confirm that the letter is
public, signed and permanent.

**Open question for operators:** a public host may be legally required to take content down.
Removing the body while keeping the hash on the ledger would preserve the record that something
was published. Nothing supports that yet, and it needs a policy decision before launch.

## API

| Endpoint                       | Access              | Returns                                                                                    |
| ------------------------------ | ------------------- | ------------------------------------------------------------------------------------------ |
| `POST /api/me/open-letters`    | signed in, enrolled | the letter; `{sentAt, body, signature, handwriting}` in                                    |
| `GET /api/me/open-letters`     | signed in           | your open letters, newest first                                                            |
| `GET /api/open-letters/{hash}` | public              | `{letterHash, author, sentAt, body, signature, handwritingHash, handwritingScore, ledger}` |

Rate limits: publishing is limited to 10 per hour per account, and reading to 600 per 10 minutes
per IP.

## Verifying in the browser

The reader page `/open/:hash` runs `OpenLettersService.verify`:

1. Recompute the hash from the author, time, handwriting hash and **the exact body shown**. It
   must equal the hash in the link, so a server can't swap in another letter or edit the text.
2. Verify the author's wallet signature over it.
3. Prove it is in the ledger. This uses `LedgerVerifier`, the same inclusion and consistency
   proofs, pinned key and remembered checkpoint as sealed letters (see [ledger.md](ledger.md)).

Signed-in readers see their petname for the author, or an _Add to contacts_ link.
