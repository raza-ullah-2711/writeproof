# Independent ledger: Merkle proofs and signed checkpoints (Task 10)

The ledger used to be checked by re-walking its hash chain from genesis. That proved the chain was
internally consistent, but the server could rewrite the whole chain consistently and no reader
would notice. It also got slower with every letter. Now the ledger is a **Merkle tree** whose
roots the server **signs** with a dedicated ledger key. Readers and outside witnesses keep the
server to what it signed.

## The tree

The tree is the Certificate Transparency tree (RFC 9162 §2.1) over the ledger's entry hashes, in
`seq` order:

```
leaf(entry) = SHA-256(0x00 || entry_hash)
node(l, r)  = SHA-256(0x01 || l || r)
```

A tree of n leaves splits at the largest power of two below n. Two proofs, each O(log n) hashes:

- **Inclusion**: entry #seq is in the tree of `size` entries with root `root`.
- **Consistency**: the tree of `newSize` entries extends the tree of `oldSize` entries unchanged.
  An operator who edits, drops or reorders any old entry cannot produce one.

`MerkleTree.java` and `frontend/src/app/letters/merkle.ts` implement the same algorithms. Both are
pinned by the CT reference roots and by shared proof vectors (`MerkleTreeTest`,
`merkle.spec.ts`). Both are also checked exhaustively: every index and size pair up to 33 leaves
on the backend and 17 on the frontend, with tampered proofs rejected.

## Checkpoints

```
message   = "writeproof/checkpoint/v1\n" size "\n" base64url(root) "\n" timestampMillis
signature = Ed25519(ledger key, message)
```

The ledger key comes from `LEDGER_SIGNING_KEY` (a base64 32-byte seed). It is separate from every
user key and from the JWT secret. `LedgerSigner` derives it, and `LedgerSignerTest` checks the
derivation against RFC 8032 test 1. A shared checkpoint vector pins the message and signature on
both sides (`LedgerSignerTest`, `checkpoint.spec.ts`).

- Every proof response carries a **fresh** checkpoint of the ledger as it is now.
- Every `LEDGER_CHECKPOINT_INTERVAL` (default 10 minutes), if the ledger grew, `CheckpointService`
  stores a checkpoint in `ledger_checkpoints` and **publishes** it. That table is append-only:
  triggers reject `UPDATE`, `DELETE` and `TRUNCATE` (migration V9).
- Publishing logs the checkpoint as JSON. If `LEDGER_CHECKPOINT_LOG` is set, it also appends the
  JSON as one line to that file (in the production stack: the `ledger_checkpoints` volume).
  `CheckpointPublisher` is the seam for adding more destinations. Published checkpoints are also
  anchored in a public transparency log (see [Anchoring in a public log](#anchoring-in-a-public-log)).

## API (public: hashes and sizes only)

| Endpoint                                    | Returns                                             |
| ------------------------------------------- | --------------------------------------------------- |
| `GET /api/ledger/key`                       | `{publicKey, rotations}`; the key in base64url      |
| `GET /api/ledger/checkpoint`                | a freshly signed checkpoint                         |
| `GET /api/ledger/checkpoints?after&limit`   | published checkpoints with `size > after`           |
| `GET /api/ledger/proof/inclusion?seq`       | `{checkpoint, entry, proof}`; 404 if no entry       |
| `GET /api/ledger/proof/consistency?from&to` | `{from, to, proof}`; 400 unless `1<=from<=to<=size` |
| `GET /api/ledger/anchors?after&limit`       | where checkpoints are anchored in the public log    |
| `GET /api/ledger/anchor/latest`             | the latest anchor and its log entry; 404 if none    |

`/entries` and `/verify` (the full chain walk) still need a login. All `GET /api/ledger/**`
requests share a per-IP limit of 600 per 10 minutes. The mock recomputes the tree for each proof.

## In the browser

When a letter is opened, `LettersService.checkLedger`:

1. Fetches the ledger key and **pins it on first use** (`LedgerTrustStore`, in localStorage). A
   different key later fails every check ("The ledger key changed…"), unless the pinned key
   handed over to it (see [Rotating the ledger key](#rotating-the-ledger-key)).
2. Fetches the inclusion proof for the letter's entry. It then checks four things:
   - the checkpoint's signature;
   - that the entry hashes to what it claims;
   - that the entry commits to the letter hash it recomputed itself;
   - that the inclusion proof leads to the signed root.
3. If it has seen a checkpoint before, it fetches a **consistency proof** from that checkpoint to
   this one. A ledger that shrank or was rewritten in between fails. It then remembers the newer
   checkpoint.

Reading a letter costs two or three small requests and O(log n) hashes. The letter shows "In the
ledger as entry #n (proven against a signed checkpoint of N entries)".

## Outside witnesses: `AuditLedger`

Anyone with the server's URL can audit it. They need no account and only the backend jar:

```bash
java -Dloader.main=com.writeproof.ledger.AuditLedger \
     -cp writeproof-backend.jar org.springframework.boot.loader.launch.PropertiesLauncher \
     https://writeproof.example --state witness.json [--key BASE64URL] \
     [--rekor URL | --no-rekor] [--anchor-grace PT6H]
```

On each run it:

- checks the key against `--key` or the key pinned in the state file, following any rotations
  from it;
- verifies every checkpoint published since its last run, plus the live one, each against the
  key in charge at its size;
- proves each checkpoint extends the one before it, starting from the checkpoint it last verified;
- holds the ledger to its anchors in the public log (see
  [Anchoring in a public log](#anchoring-in-a-public-log)).

On success it saves the new state and exits 0. A changed key, a bad signature, a shrunk ledger or
rewritten history exits 1. Run it on a schedule from machines the operator doesn't control. Each
witness then holds the operator to an append-only history. `LedgerProofApiTests` checks that it
catches an entry rewritten directly in the database.

## Rotating the ledger key

The old key hands the ledger over to the new one by signing a rotation statement:

```
message   = "writeproof/key-rotation/v1\n" base64url(oldKey) "\n" base64url(newKey) "\n"
            size "\n" base64url(root) "\n" timestampMillis
signature = Ed25519(old key, message)
```

`size` and `root` are the ledger when the key changed: the old key vouches for checkpoints up to
`size` and the new key from `size` on. History must pass through that checkpoint, so the new key
can't rewrite anything the old key signed. A shared vector pins the message and signature on both
sides (`KeyRotationTest`, `key-rotation.spec.ts`).

To rotate, keep the current key and add a new one, then restart:

```bash
# deploy/.env
LEDGER_PREVIOUS_SIGNING_KEY=<the current LEDGER_SIGNING_KEY>
LEDGER_SIGNING_KEY=<new: openssl rand -base64 32>
```

At startup, before it answers requests, the server signs the statement with the previous key and
stores it in `ledger_key_rotations` (append-only, migration V17). Restarting with the same settings
changes nothing, and `LEDGER_PREVIOUS_SIGNING_KEY` can be removed once the log says "already
rotated". Stop every API instance before rotating, so no old instance keeps signing past the
handover. Back up the new `deploy/.env` (see [deployment.md](deployment.md)).

The server also refuses to start when `LEDGER_SIGNING_KEY` isn't the ledger's key (the last
rotation's new key or, before any rotation, the key that signed the published checkpoints). It
also refuses to start when `LEDGER_PREVIOUS_SIGNING_KEY` isn't the ledger's key, and when it would
rotate back to a retired key. A key swapped by mistake used to make every client refuse the
ledger; now the server stops first.

`GET /api/ledger/key` returns `{publicKey, rotations}`, every rotation oldest first. Browsers and
witnesses that pinned an older key follow the chain from it. Each statement must be signed by the
key before it, and the chain must end at the server's key. They then prove that the ledger they saw
extends to each handover checkpoint, and from there to the current one. A changed key with no such
chain still fails, as before.

## Anchoring in a public log

A dishonest operator could show each user a different, internally consistent ledger. Every
check above would pass for each of them. To rule that out, every published checkpoint is
**anchored** in a public, append-only transparency log the operator doesn't run: Sigstore's
[Rekor](https://rekor.sigstore.dev) (`LEDGER_REKOR_URL`). Then every history the ledger key ever
vouched for, true or forked, sits in one log everyone can read.

**The entry.** A Rekor v1 `dsse` entry. Its payload is the checkpoint's signed message
(`writeproof/checkpoint/v1\n…`, payload type `application/vnd.writeproof.checkpoint+text`), signed
by the ledger key over DSSE's pre-authentication encoding. Rekor keeps only the payload's hash. It
indexes the entry by that hash and by the SHA-256 of the verifier key exactly as submitted. So the
key is always written in one canonical form (PEM of its SubjectPublicKeyInfo, one base64 line), and
clients accept no other. (Rekor's `hashedrekord` type can't be used: it checks Ed25519 only in the
pre-hashed Ed25519ph form.)

**The server.** `AnchorService` anchors published checkpoints every `LEDGER_ANCHOR_INTERVAL`
(default 5 minutes), oldest first, and simply retries if the log is unreachable. It records each
anchor in `ledger_anchors` (append-only, migration V18) and lists them at `GET /api/ledger/anchors`.
`GET /api/ledger/anchor/latest` relays the latest anchor's log entry for browsers. Browsers may only
contact this origin, but the relay can't forge anything they check.

**Witnesses** (`AuditLedger`, against Sigstore's log by default, `--rekor URL` or `--no-rekor`):

- pin the log's key on first use, like the ledger key;
- check each new anchor in the log itself. That means the entry's payload is the published
  checkpoint and the DSSE signature is the key in charge at that size. The entry hashes to its UUID,
  its inclusion proof leads to the log's tree head, and the log's key signed that tree head;
- fail if a published checkpoint stays unanchored longer than `--anchor-grace` (default 6 hours);
- search the log for **everything** under each ledger key. An entry whose payload matches no
  checkpoint the server publishes means the key vouched for a history the server hides. That is
  the evidence of a split view.

**Browsers** (production builds; `PUBLIC_LOG` in `app.config.ts`) close the remaining gap. A server
could show one user a forked ledger and simply never anchor it. Every browser remembers the oldest
checkpoint it has seen beyond what it has verified as anchored. After 24 hours, that checkpoint must
be covered by an anchor the browser verifies itself, against Rekor's pinned key, and the anchored
ledger must extend it. Otherwise the letter is flagged. So a fork has to be anchored publicly too,
where witnesses find it next to the real history. Or the user it was shown to is told.

Tests: `RekorTest` and `rekor.spec.ts` verify a real entry from rekor.sigstore.dev (the backend and
the browser write the key exactly as it was indexed). `AnchorTests` runs the server and a witness
against a fake log that follows Rekor's API, and catches a never-anchored checkpoint and an anchored
but hidden fork. `ledger-verifier.spec.ts` covers the browser's 24-hour rule.

Local and CI stacks (`generate-env.sh localhost`) don't anchor, so nothing is written to the public
log. A production build served by such a stack flags letters after a day, as it should.

## What this does and doesn't guarantee

- **Rewrites are caught by anyone who saw an earlier checkpoint:** a browser that opened a
  letter, or a witness. The operator can't edit history without either losing the key or being
  unable to prove consistency.
- **Split views are caught through the public log.** Every history a browser accepts for more
  than a day is anchored in Rekor, and witnesses search Rekor for everything under the ledger key.
  Showing someone a different history is therefore detected within a day: either by that user's
  browser, or by any witness, as two anchored histories. This needs at least one witness running,
  and it trusts Rekor not to split its own view (Rekor is witnessed by Sigstore's ecosystem).
- **The browser checks run in the code the server sends.** Like any web app, an operator who
  serves modified JavaScript can skip them. They protect against a compromised API or database
  behind an honest frontend, and they make a dishonest server detectable by anyone who checks.
- **Trust on first use.** A browser that has never seen the ledger accepts whatever key it's
  given. The key could also be shipped in the app build or published out of band.
- **Rotation needs the old key.** A planned rotation is signed by the old key, so clients and
  witnesses follow it. A **lost** key can't sign a handover, so losing it still makes every client
  and witness refuse the ledger: back it up with the other secrets (see
  [deployment.md](deployment.md)). A **stolen** key can sign a handover to the thief's key. Rotation
  doesn't help there; witnesses comparing notes would show two handovers from one key.
- **Real chain.** `LedgerService` is still the seam. A chain-backed implementation can serve the
  same proofs, or point clients at the chain's own.
