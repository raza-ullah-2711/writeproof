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
  `CheckpointPublisher` is the seam for adding more destinations: a transparency log, a
  timestamping service or a public chain.

## API (public: hashes and sizes only)

| Endpoint                                    | Returns                                             |
| ------------------------------------------- | --------------------------------------------------- |
| `GET /api/ledger/key`                       | `{publicKey, rotations}`; the key in base64url      |
| `GET /api/ledger/checkpoint`                | a freshly signed checkpoint                         |
| `GET /api/ledger/checkpoints?after&limit`   | published checkpoints with `size > after`           |
| `GET /api/ledger/proof/inclusion?seq`       | `{checkpoint, entry, proof}`; 404 if no entry       |
| `GET /api/ledger/proof/consistency?from&to` | `{from, to, proof}`; 400 unless `1<=from<=to<=size` |

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
     https://writeproof.example --state witness.json [--key BASE64URL]
```

On each run it:

- checks the key against `--key` or the key pinned in the state file, following any rotations
  from it;
- verifies every checkpoint published since its last run, plus the live one, each against the
  key in charge at its size;
- proves each checkpoint extends the one before it, starting from the checkpoint it last verified.

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

## What this does and doesn't guarantee

- **Rewrites are caught by anyone who saw an earlier checkpoint:** a browser that opened a
  letter, or a witness. The operator can't edit history without either losing the key or being
  unable to prove consistency.
- **Split views need comparing notes.** A malicious operator could show different users
  different, internally consistent ledgers. A browser on its own can't tell. Witnesses comparing
  their checkpoints (or one public anchor everyone checks) can. Gossip between clients, or
  anchoring checkpoints on a public chain via `CheckpointPublisher`, is the next step.
- **Trust on first use.** A browser that has never seen the ledger accepts whatever key it's
  given. The key could also be shipped in the app build or published out of band.
- **Rotation needs the old key.** A planned rotation is signed by the old key, so clients and
  witnesses follow it. A **lost** key can't sign a handover, so losing it still makes every client
  and witness refuse the ledger: back it up with the other secrets (see
  [deployment.md](deployment.md)). A **stolen** key can sign a handover to the thief's key. Rotation
  doesn't help there; witnesses comparing notes would show two handovers from one key.
- **Real chain.** `LedgerService` is still the seam. A chain-backed implementation can serve the
  same proofs, or point clients at the chain's own.
