import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { fromBase64Url, toBase64Url } from '../crypto/base64url';
import { verifyEd25519 } from '../crypto/ed25519';
import { unwrapFor, wrapFor } from '../letters/letter-crypto';
import { letterSignedMessage, sha256 } from '../letters/letter-format';
import { LedgerVerifier } from '../letters/ledger-verifier';
import { LedgerEntry } from '../letters/ledger-verify';
import { verifiedEncryptionKey } from '../letters/verified-account';
import { WalletService } from '../wallet/wallet.service';
import { ArchiveReader, archiveEntryHash, archiveHeader, photoAad } from './archive-format';
import { MAX_PHOTO_BYTES, PreparedPhoto } from './photo-prep';

/** An archive entry as the server returns it. Nothing here is trusted until `open`. */
export interface ArchiveEntry {
  entryHash: string;
  /** The owner's address: who added it. */
  owner: string;
  addedAt: string;
  iv: string;
  ciphertext: string;
  signature: string;
  /** Hashes of the encrypted photos, in order. */
  photos: string[];
  /** The owner first, then everyone it's shared with. */
  readers: ArchiveReader[];
  ledger: LedgerEntry;
}

/** What's sealed inside: only the readers can see it. */
export interface ArchiveDescription {
  title: string;
  /** When the original was written, in the owner's words (e.g. "Spring 1962"); may be empty. */
  writtenOn: string;
  note: string;
  photos: { width: number; height: number }[];
}

/** What the reader's own browser established. */
export interface OpenedEntry {
  description: ArchiveDescription | null;
  /** The entry hash recomputes from what was sent, and the owner's wallet signed it. */
  signatureValid: boolean;
  /** Decrypted with this wallet's key: sealed for us and unaltered. */
  decrypted: boolean;
  ledgerValid: boolean;
  ledgerProblem: string | null;
  ledgerCheckpointSize: number | null;
  /** For decrypting the photos; null if the entry couldn't be opened. */
  key: CryptoKey | null;
}

export const MAX_ARCHIVE_PHOTOS = 10;
/** The owner plus up to 20 people. */
export const MAX_SHARED_WITH = 20;
export const MAX_TITLE = 200;
export const MAX_WRITTEN_ON = 100;
export const MAX_NOTE = 5000;

/**
 * The private family archive: photos of paper letters sealed end to end to the people the owner
 * chooses, signed by their wallet and recorded on the ledger (docs/family-archive.md).
 */
@Injectable({ providedIn: 'root' })
export class ArchiveService {
  private readonly http = inject(HttpClient);
  private readonly wallet = inject(WalletService);
  private readonly ledger = inject(LedgerVerifier);

  /**
   * Seals and adds an entry: each photo is encrypted and uploaded, then the description and the
   * content key (wrapped for the owner and each person in `shareWith`), signed together.
   */
  async add(
    photos: readonly PreparedPhoto[],
    text: { title: string; writtenOn: string; note: string },
    shareWith: readonly string[],
    progress: (done: number, total: number) => void = () => undefined,
  ): Promise<ArchiveEntry> {
    const me = this.wallet.publicKey();
    const myEncryptionKey = this.wallet.encryptionPublicKey();
    if (!me || !myEncryptionKey) {
      throw new Error('No wallet is loaded');
    }
    const title = text.title.trim();
    if (!title || title.length > MAX_TITLE) {
      throw new Error(`A title needs 1 to ${MAX_TITLE} characters`);
    }
    if (text.writtenOn.length > MAX_WRITTEN_ON || text.note.length > MAX_NOTE) {
      throw new Error('The date or the note is too long');
    }
    if (photos.length === 0 || photos.length > MAX_ARCHIVE_PHOTOS) {
      throw new Error(`An entry needs 1 to ${MAX_ARCHIVE_PHOTOS} photos`);
    }
    if (photos.some((p) => p.bytes.length > MAX_PHOTO_BYTES)) {
      throw new Error('A photo is too large');
    }
    const others = [...new Set(shareWith)].filter((a) => a !== me);
    if (others.length > MAX_SHARED_WITH) {
      throw new Error(`An entry can be shared with at most ${MAX_SHARED_WITH} people`);
    }
    // Check everyone's key before uploading anything.
    const readerKeys: [string, string][] = [[me, myEncryptionKey]];
    for (const address of others) {
      readerKeys.push([address, await verifiedEncryptionKey(this.http, address)]);
    }

    const addedAt = new Date().toISOString();
    const rawKey = crypto.getRandomValues(new Uint8Array(32));
    const key = await crypto.subtle.importKey('raw', rawKey, 'AES-GCM', false, ['encrypt']);
    const photoHashes: string[] = [];
    for (let i = 0; i < photos.length; i++) {
      progress(i, photos.length);
      const iv = crypto.getRandomValues(new Uint8Array(12));
      const ciphertext = new Uint8Array(
        await crypto.subtle.encrypt(
          { name: 'AES-GCM', iv, additionalData: photoAad(me, addedAt, i) },
          key,
          photos[i].bytes,
        ),
      );
      const blob = new Uint8Array(12 + ciphertext.length);
      blob.set(iv, 0);
      blob.set(ciphertext, 12);
      const { hash } = await firstValueFrom(
        this.http.post<{ hash: string }>('/api/me/archive/photos', new Blob([blob]), {
          headers: new HttpHeaders({ 'Content-Type': 'application/octet-stream' }),
        }),
      );
      if (hash !== toBase64Url(await sha256(blob))) {
        throw new Error('The server recorded a different photo');
      }
      photoHashes.push(hash);
    }
    progress(photos.length, photos.length);

    const header = archiveHeader(me, addedAt, photoHashes);
    const aad = new TextEncoder().encode(header);
    const description: ArchiveDescription = {
      title,
      writtenOn: text.writtenOn.trim(),
      note: text.note,
      photos: photos.map((p) => ({ width: p.width, height: p.height })),
    };
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ciphertext = new Uint8Array(
      await crypto.subtle.encrypt(
        { name: 'AES-GCM', iv, additionalData: aad },
        key,
        new TextEncoder().encode(JSON.stringify(description)),
      ),
    );
    const readers: ArchiveReader[] = [];
    for (const [address, encryptionKey] of readerKeys) {
      readers.push({
        publicKey: address,
        ...(await wrapFor(fromBase64Url(encryptionKey), rawKey, aad)),
      });
    }
    rawKey.fill(0);
    const ivText = toBase64Url(iv);
    const ciphertextText = toBase64Url(ciphertext);
    const hash = await archiveEntryHash(header, ivText, ciphertextText, readers);
    const signature = await this.wallet.sign(letterSignedMessage(hash));
    const entry = await firstValueFrom(
      this.http.post<ArchiveEntry>('/api/me/archive', {
        addedAt,
        iv: ivText,
        ciphertext: ciphertextText,
        photos: photoHashes,
        readers,
        signature: toBase64Url(signature),
      }),
    );
    if (entry.entryHash !== toBase64Url(hash)) {
      throw new Error('The server recorded a different entry');
    }
    return entry;
  }

  /** Entries this wallet can open: its own and those shared with it, newest first. */
  list(): Promise<ArchiveEntry[]> {
    return firstValueFrom(this.http.get<ArchiveEntry[]>('/api/me/archive'));
  }

  /** Checks an entry without trusting the server, and opens its description. */
  async open(entry: ArchiveEntry): Promise<OpenedEntry> {
    const me = this.wallet.publicKey();
    const myEncryptionKey = this.wallet.encryptionPublicKey();
    if (!me || !myEncryptionKey) {
      throw new Error('No wallet is loaded');
    }
    const header = archiveHeader(entry.owner, entry.addedAt, entry.photos);
    const hash = await archiveEntryHash(header, entry.iv, entry.ciphertext, entry.readers);
    const signatureValid =
      toBase64Url(hash) === entry.entryHash &&
      entry.readers[0]?.publicKey === entry.owner &&
      (await verifyEd25519(entry.owner, letterSignedMessage(hash), entry.signature));

    let description: ArchiveDescription | null = null;
    let key: CryptoKey | null = null;
    const mine = entry.readers.find((r) => r.publicKey === me);
    if (mine) {
      try {
        const aad = new TextEncoder().encode(header);
        const raw = await unwrapFor(mine, aad, myEncryptionKey, (k) => this.wallet.ecdh(k));
        key = await crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['decrypt']);
        const plaintext = await crypto.subtle.decrypt(
          { name: 'AES-GCM', iv: fromBase64Url(entry.iv), additionalData: aad },
          key,
          fromBase64Url(entry.ciphertext),
        );
        description = parseDescription(new TextDecoder().decode(plaintext), entry.photos.length);
      } catch {
        description = null;
        key = null;
      }
    }
    const ledger = await this.ledger.verifyEntry(entry.ledger, toBase64Url(hash));
    return {
      description,
      signatureValid,
      decrypted: description !== null,
      ledgerValid: ledger.problem === null,
      ledgerProblem: ledger.problem,
      ledgerCheckpointSize: ledger.problem === null ? ledger.size : null,
      key,
    };
  }

  /** Fetches and decrypts photo `index` of an opened entry; checks it is the one signed. */
  async photo(entry: ArchiveEntry, key: CryptoKey, index: number): Promise<Uint8Array> {
    const blob = new Uint8Array(
      await firstValueFrom(
        this.http.get(`/api/me/archive/photos/${encodeURIComponent(entry.photos[index])}`, {
          responseType: 'arraybuffer',
        }),
      ),
    );
    if (toBase64Url(await sha256(blob)) !== entry.photos[index]) {
      throw new Error('This photo is not the one that was signed');
    }
    return new Uint8Array(
      await crypto.subtle.decrypt(
        {
          name: 'AES-GCM',
          iv: blob.subarray(0, 12),
          additionalData: photoAad(entry.owner, entry.addedAt, index),
        },
        key,
        blob.subarray(12),
      ),
    );
  }
}

function parseDescription(json: string, photoCount: number): ArchiveDescription {
  const d = JSON.parse(json) as ArchiveDescription;
  if (
    typeof d?.title !== 'string' ||
    typeof d.writtenOn !== 'string' ||
    typeof d.note !== 'string' ||
    !Array.isArray(d.photos) ||
    d.photos.length !== photoCount
  ) {
    throw new Error('Malformed archive entry');
  }
  return d;
}
