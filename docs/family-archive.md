# Family archive (Task 14c)

Photos of paper letters that should never be lost: Grandma's letters, old postcards, notes in a
book. Unlike everything else in Writeproof, an archived letter isn't written in the app, so
Writeproof proves what it can, and says plainly what it can't.

| Writeproof proves                                                           | It can't prove                                                                   |
| --------------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| who added the copy (their wallet signed it)                                 | who wrote the original                                                           |
| that the photos and words haven't changed since (signed, and on the ledger) | when the original was written ("Written spring 1962" is the owner's description) |
| that only the chosen people can open it (sealed end to end)                 |                                                                                  |

Every entry is labelled "Archived copy, added by ⟨name⟩ on ⟨date⟩".

## Decisions

| Question               | Decision                                                                                                                                                           |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Who can see an entry   | The owner chooses per entry: **private** (sealed to people they pick) or **public by link**. Private ships first (14c-1). Public waits for image scanning (14c-2). |
| Scanning public photos | **PhotoDNA**: apply to Microsoft. Public entries stay off until it's in place. Private entries are end-to-end encrypted and never visible to the server.           |
| Photos per entry       | Up to 10, each re-encoded to at most 2400 px and 1.5 MB.                                                                                                           |
| Who can add            | Any signed-in account; no handwriting needed. Rate limited.                                                                                                        |

## Private entries (14c-1, built)

**In the browser, before anything leaves it:**

1. **Each photo is prepared** (`photo-prep.ts`): turned upright, scaled to at most 2400 px, and
   re-encoded as a JPEG through a canvas. Re-encoding keeps only the pixels, so no location,
   camera or date metadata (EXIF) is uploaded. This was checked in a real browser with a photo
   carrying GPS data: the prepared JPEG had no EXIF segment and no location.
2. **A random content key** encrypts each photo (AES-256-GCM, its own IV, associated data
   `writeproof/archive-photo/v1\n owner \n addedAt \n index`, so a photo can't be moved to another
   entry or position). Each encrypted photo, `iv ‖ ciphertext`, is uploaded on its own and named
   by its SHA-256.
3. **The description** (title, "written on", note, photo sizes) is encrypted with the same key,
   with the header as associated data.
4. **The key is wrapped** for the owner and for each person it's shared with, exactly as sealed
   letters wrap theirs. Each person's encryption key is used only if their identity key signed it
   (`verified-account.ts`, shared with letters).
5. **The owner's wallet signs the entry hash**, and the ledger records it.

```
header    = "writeproof/archive/v1\n" owner "\n" addedAt "\n" photoHash₁ "," photoHash₂ …
entryHash = SHA-256(header \n iv \n ciphertext \n for each reader: address \n ephemeralKey \n iv \n wrappedKey)
signed    = "writeproof/letter-signature/v1\n" b64url(entryHash)   (the letters' message format)
```

The distinct domain keeps an entry's hash from ever equalling a letter's. `ArchiveHashing` (Java)
and `archive-format.ts` share a test vector.

**On the server** (`archive` package, migration V23), which only ever holds ciphertext:

- `archive_blobs`: the encrypted photos. They're uploaded first, then attached once to the entry
  that signs them. An upload that was never attached is deleted after a day; an attached photo can
  never be deleted.
- `archive_entries` and `archive_readers`: append-only, like letters. There are no edit or
  delete endpoints.
- **Limits:**
  - 10 photos and 20 people per entry;
  - 1.5 MB per photo;
  - 30 uploads waiting at a time;
  - 500 MB per account;
  - 120 uploads and 20 entries per hour.
- **Access:** only the owner and the people an entry is shared with can list it or fetch its
  photos. To anyone else, an entry and a missing entry look the same (404).

**Opening** (`ArchiveService.open`) trusts nothing from the server:

- it recomputes the entry hash from what was sent, and checks the owner's signature;
- the owner must be the first reader;
- it decrypts with this wallet's key, which authenticates the description;
- it checks the ledger proofs;
- each photo must hash to the signed photo hash before it is decrypted.

## API (14c-1)

| Endpoint                            | Access    |                                                                     |
| ----------------------------------- | --------- | ------------------------------------------------------------------- |
| `POST /api/me/archive/photos`       | signed in | an encrypted photo (`application/octet-stream`) → `{hash}`          |
| `POST /api/me/archive`              | signed in | `{addedAt, iv, ciphertext, photos, readers, signature}` → the entry |
| `GET /api/me/archive`               | signed in | entries you can open, newest first                                  |
| `GET /api/me/archive/{hash}`        | a reader  | one entry                                                           |
| `GET /api/me/archive/photos/{hash}` | a reader  | an encrypted photo (cached privately, immutable)                    |

## Public entries (14c-2, waiting for PhotoDNA)

These will be signed and recorded the same way, but readable by anyone with the link, like open
letters. Each photo will be checked against PhotoDNA before it becomes visible. Entries will be
reportable, removable by moderators (with the same holds, appeals and child-safety preservation as
open letters), and blanked on account deletion. Until PhotoDNA is in place, the option is off.
