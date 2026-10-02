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
| `GET /api/ledger/key`                       | `{publicKey}`, the raw Ed25519 key in base64url     |
| `GET /api/ledger/checkpoint`                | a freshly signed checkpoint                         |
| `GET /api/ledger/checkpoints?after&limit`   | published checkpoints with `size > after`           |
| `GET /api/ledger/proof/inclusion?seq`       | `{checkpoint, entry, proof}`; 404 if no entry       |
| `GET /api/ledger/proof/consistency?from&to` | `{from, to, proof}`; 400 unless `1<=from<=to<=size` |

`/entries` and `/verify` (the full chain walk) still need a login. All `GET /api/ledger/**`
requests share a per-IP limit of 600 per 10 minutes. The mock recomputes the tree for each proof.

## In the browser

When a letter is opened, `LettersService.checkLedger`:

1. Fetches the ledger key and **pins it on first use** (`LedgerTrustStore`, in localStorage). A
   different key later fails every check ("The ledger key changed…").
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

- checks the key against `--key` or the key pinned in the state file;
- verifies every checkpoint published since its last run, plus the live one;
- proves each checkpoint extends the one before it, starting from the checkpoint it last verified.

On success it saves the new state and exits 0. A changed key, a bad signature, a shrunk ledger or
rewritten history exits 1. Run it on a schedule from machines the operator doesn't control. Each
witness then holds the operator to an append-only history. `LedgerProofApiTests` checks that it
catches an entry rewritten directly in the database.

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
- **The ledger key is long-lived.** Changing it makes every client and witness refuse the ledger.
  Back it up with the other secrets (see [deployment.md](deployment.md)). Rotation, where the old
  key signs the new one, isn't built yet.
- **Real chain.** `LedgerService` is still the seam. A chain-backed implementation can serve the
  same proofs, or point clients at the chain's own.
