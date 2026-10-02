# Contacts (Task 11a)

The first slice of the social layer. Contacts are **petnames**: the names _you_ give addresses.
"Mum" means whatever address you filed under "Mum". Nobody else can claim that name, and nobody
learns that you use it.

## Decisions

- **Address exchange only.** There is no directory, search or public handle. You add someone by
  scanning their QR code, opening a link they sent you, or pasting their address.
- **Encrypted on the server.** The contact book is encrypted in the browser with a key only the
  wallet can derive. The server stores ciphertext and a version number. It never sees names or
  who your contacts are, beyond their count, bounded by the ciphertext size.
- **Names are unique** (case-insensitive) within your book, and addresses appear once. A
  newcomer can't take a name you already use for someone else, so a name always means one person.

## Sharing an address

The Contacts page shows your address as a QR code of a link:

```
https://<your server>/contacts?add=<address>
```

A phone camera scan opens the app with the address filled in. The QR code is drawn on a canvas
(`uqr` encodes it), so no HTML strings are involved under Trusted Types. Opening a shared link
**only fills in the form**. You still choose the name and confirm, and the page warns you to add
the address only if you know whose it is.

On adding, the app checks that the address is well formed, isn't your own, and belongs to an
account. That last check uses `GET /api/accounts/by-key/{address}`, and the answer must name the
same key.

## Encryption

```
secret = X25519(wallet encryption private key, wallet encryption public key)
key    = HKDF-SHA256(secret, salt = "writeproof/contacts/v1", info = "key\n" address) → AES-256-GCM
blob   = iv(12) || AES-GCM(key, iv, JSON(book), aad = "writeproof/contacts/v1\n" address "\n" version)
```

- Only the wallet can compute `secret`. A wallet restored from its recovery code computes the same
  one, so the book follows you to a new device.
- The associated data binds the blob to the account and to the version it's stored as. The server
  can't serve one account's book to another, or relabel an old book as the current version.
- The browser remembers the highest version it has seen. If the server returns an older one (a
  rollback), the browser rejects it. A browser that has never seen the book can't detect this.

## API

| Endpoint               | Body / result                                                                                                |
| ---------------------- | ------------------------------------------------------------------------------------------------------------ |
| `GET /api/me/contacts` | `{version, ciphertext, updatedAt}`; version 0 and no ciphertext before the first save                        |
| `PUT /api/me/contacts` | `{baseVersion, ciphertext}` → `{version: baseVersion + 1, …}`; 409 if the stored version isn't `baseVersion` |

- The ciphertext is at most 256 KiB of plaintext.
- Writes are rate-limited to 120 per hour per account.
- The `contact_books` table is migration V10.
- A write based on an old version fails with 409, so two devices can't silently overwrite each
  other. The client then reloads and applies the same edit again, once. If the edit no longer
  makes sense (the name was taken in the meantime), you see why.

## In the app

- **Contacts page**: share your address, add a contact, rename, remove (asks first), and _Write_,
  which opens Letters addressed to them.
- **Letters**:
  - The recipient field suggests your contacts and shows who an address is ("To Bob from choir").
  - Inbox and Sent show your names for people.
  - Unknown senders get an _Add to contacts_ link.
  - The send notice names the recipient.

## Next slices

- **Threads**: replies that are signed and point to the hash of the letter they answer.
- **Open letters**: signed by hand and wallet but not sealed, readable by anyone with the link.
