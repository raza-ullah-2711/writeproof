import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { IDBFactory } from 'fake-indexeddb';
import { nextRequest } from '../../testing/http';
import { fromBase64Url, toBase64Url } from '../crypto/base64url';
import { verifyEd25519 } from '../crypto/ed25519';
import { unwrapFor } from '../letters/letter-crypto';
import { encryptionKeyBinding, letterSignedMessage, sha256 } from '../letters/letter-format';
import { LedgerVerifier } from '../letters/ledger-verifier';
import { WalletService } from '../wallet/wallet.service';
import { ArchiveReader, archiveEntryHash, archiveHeader } from './archive-format';
import { ArchiveEntry, ArchiveService } from './archive.service';
import { PreparedPhoto } from './photo-prep';

/** Someone to share with, whose keys live in the test. */
async function relative() {
  const identity = (await crypto.subtle.generateKey({ name: 'Ed25519' }, false, [
    'sign',
    'verify',
  ])) as CryptoKeyPair;
  const encryption = (await crypto.subtle.generateKey({ name: 'X25519' }, false, [
    'deriveBits',
  ])) as CryptoKeyPair;
  const publicKey = toBase64Url(
    new Uint8Array(await crypto.subtle.exportKey('raw', identity.publicKey)),
  );
  const encryptionKey = toBase64Url(
    new Uint8Array(await crypto.subtle.exportKey('raw', encryption.publicKey)),
  );
  const signature = new Uint8Array(
    await crypto.subtle.sign(
      'Ed25519',
      identity.privateKey,
      encryptionKeyBinding(publicKey, encryptionKey),
    ),
  );
  return {
    publicKey,
    encryptionKey,
    ecdh: async (ephemeral: Uint8Array<ArrayBuffer>) => {
      const key = await crypto.subtle.importKey('raw', ephemeral, { name: 'X25519' }, false, []);
      return crypto.subtle.deriveBits({ name: 'X25519', public: key }, encryption.privateKey, 256);
    },
    account: {
      accountId: 'gran',
      publicKey,
      createdAt: '',
      encryptionKey,
      encryptionKeySignature: toBase64Url(signature),
    },
  };
}

const photo = (seed: number): PreparedPhoto => ({
  bytes: Uint8Array.from({ length: 300 }, (_, i) => (seed + i) & 0xff),
  width: 1200,
  height: 1600,
});

describe('ArchiveService', () => {
  let archive: ArchiveService;
  let wallet: WalletService;
  let http: HttpTestingController;
  let verifyEntry: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    globalThis.indexedDB = new IDBFactory();
    verifyEntry = vi.fn().mockResolvedValue({ problem: null, size: 3 });
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: LedgerVerifier, useValue: { verifyEntry } },
      ],
    });
    archive = TestBed.inject(ArchiveService);
    wallet = TestBed.inject(WalletService);
    http = TestBed.inject(HttpTestingController);
    await wallet.create();
  });

  afterEach(() => http.verify());

  /** Adds an entry, playing the server: stores uploads and returns the entry as stored. */
  async function addEntry(
    photos: PreparedPhoto[],
    shareWith: Awaited<ReturnType<typeof relative>>[] = [],
  ): Promise<{ entry: ArchiveEntry; blobs: Map<string, Uint8Array> }> {
    const blobs = new Map<string, Uint8Array>();
    const adding = archive.add(
      photos,
      { title: 'From Grandma, 1962', writtenOn: 'Spring 1962', note: 'Found in the attic.' },
      shareWith.map((r) => r.publicKey),
    );
    for (const r of shareWith) {
      (await nextRequest(http, `/api/accounts/by-key/${r.publicKey}`)).flush(r.account);
    }
    for (let i = 0; i < photos.length; i++) {
      const upload = await nextRequest(http, '/api/me/archive/photos');
      const blob = new Uint8Array(await (upload.request.body as Blob).arrayBuffer());
      const hash = toBase64Url(await sha256(blob));
      blobs.set(hash, blob);
      upload.flush({ hash });
    }
    const post = await nextRequest(http, '/api/me/archive');
    const body = post.request.body;
    const entry: ArchiveEntry = {
      ...body,
      owner: wallet.publicKey()!,
      entryHash: toBase64Url(
        await archiveEntryHash(
          archiveHeader(wallet.publicKey()!, body.addedAt, body.photos),
          body.iv,
          body.ciphertext,
          body.readers,
        ),
      ),
      ledger: { seq: 5, prevHash: '', payloadHash: '', recordedAtMillis: 0, entryHash: '' },
    };
    post.flush(entry);
    await adding;
    return { entry, blobs };
  }

  async function photoOf(
    entry: ArchiveEntry,
    key: CryptoKey,
    blobs: Map<string, Uint8Array>,
    i = 0,
  ) {
    const fetching = archive.photo(entry, key, i);
    const req = await nextRequest(http, `/api/me/archive/photos/${entry.photos[i]}`);
    req.flush(blobs.get(entry.photos[i])!.buffer);
    return fetching;
  }

  it('encrypts every photo before upload and signs the entry, owner first', async () => {
    const { entry, blobs } = await addEntry([photo(1), photo(2)]);

    for (const blob of blobs.values()) {
      expect(blob.length).toBe(12 + 300 + 16); // iv, ciphertext, tag
      expect([...blob.subarray(12, 20)]).not.toEqual([1, 2, 3, 4, 5, 6, 7, 8]);
    }
    expect(entry.readers.map((r) => r.publicKey)).toEqual([wallet.publicKey()]);
    expect(
      await verifyEd25519(
        entry.owner,
        letterSignedMessage(fromBase64Url(entry.entryHash)),
        entry.signature,
      ),
    ).toBe(true);
  });

  it('opens and verifies an entry, and decrypts its photos', async () => {
    const { entry, blobs } = await addEntry([photo(7)]);

    const opened = await archive.open(entry);

    expect(opened.signatureValid).toBe(true);
    expect(opened.decrypted).toBe(true);
    expect(opened.ledgerValid).toBe(true);
    expect(opened.description).toEqual({
      title: 'From Grandma, 1962',
      writtenOn: 'Spring 1962',
      note: 'Found in the attic.',
      photos: [{ width: 1200, height: 1600 }],
    });
    expect(await photoOf(entry, opened.key!, blobs)).toEqual(photo(7).bytes);
    expect(verifyEntry).toHaveBeenCalledWith(entry.ledger, entry.entryHash);
  });

  it('wraps the key for everyone it is shared with, after checking their keys', async () => {
    const gran = await relative();
    const { entry } = await addEntry([photo(3)], [gran]);

    expect(entry.readers.map((r) => r.publicKey)).toEqual([wallet.publicKey(), gran.publicKey]);
    const header = archiveHeader(entry.owner, entry.addedAt, entry.photos);
    const raw = await unwrapFor(
      entry.readers[1],
      new TextEncoder().encode(header),
      gran.encryptionKey,
      gran.ecdh,
    );
    expect(new Uint8Array(raw)).toHaveLength(32);
  });

  it('flags an entry whose readers or photos were changed after signing', async () => {
    const { entry, blobs } = await addEntry([photo(4)]);
    const opened = await archive.open(entry);

    const extra: ArchiveReader = { ...entry.readers[0], publicKey: 'someone-else' };
    expect(
      (await archive.open({ ...entry, readers: [...entry.readers, extra] })).signatureValid,
    ).toBe(false);
    const tampered = new Map(blobs);
    const blob = new Uint8Array(blobs.get(entry.photos[0])!);
    blob[20] ^= 1;
    tampered.set(entry.photos[0], blob);
    await expect(photoOf(entry, opened.key!, tampered)).rejects.toThrow(
      'not the one that was signed',
    );
  });

  it('refuses entries without photos or a title, before uploading anything', async () => {
    await expect(archive.add([], { title: 'x', writtenOn: '', note: '' }, [])).rejects.toThrow(
      '1 to 10 photos',
    );
    await expect(
      archive.add([photo(1)], { title: ' ', writtenOn: '', note: '' }, []),
    ).rejects.toThrow('title');
  });
});

describe('archive format (shared vector with backend ArchiveApiTests)', () => {
  const bytes = (from: number, n: number) =>
    toBase64Url(Uint8Array.from({ length: n }, (_, i) => (from + i) & 0xff));

  it('builds the header and the entry hash', async () => {
    const header = archiveHeader(bytes(1, 32), '2026-10-14T10:00:00.000Z', [
      bytes(7, 32),
      bytes(40, 32),
    ]);
    expect(header).toBe(
      'writeproof/archive/v1\n' +
        'AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA\n' +
        '2026-10-14T10:00:00.000Z\n' +
        'BwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAhIiMkJSY,KCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4_QEFCQ0RFRkc',
    );
    const hash = await archiveEntryHash(header, bytes(100, 12), bytes(120, 40), [
      {
        publicKey: bytes(1, 32),
        ephemeralPublicKey: bytes(150, 32),
        iv: bytes(200, 12),
        wrappedKey: bytes(210, 48),
      },
      {
        publicKey: bytes(101, 32),
        ephemeralPublicKey: bytes(60, 32),
        iv: bytes(90, 12),
        wrappedKey: bytes(10, 48),
      },
    ]);
    expect(toBase64Url(hash)).toBe('mTtvY-Sz7oFlLICBO-fgNXULY7hjudpBbZzU-F2-Kso');
  });
});
