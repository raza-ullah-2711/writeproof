import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { IDBFactory } from 'fake-indexeddb';
import { nextRequest } from '../../testing/http';
import { fromBase64Url, toBase64Url } from '../crypto/base64url';
import { verifyEd25519 } from '../crypto/ed25519';
import { WalletService } from '../wallet/wallet.service';
import { GENESIS_PREV_HASH, LedgerEntry, entryHash } from './ledger-verify';
import {
  encryptionKeyBinding,
  letterHash,
  letterHeader,
  letterSignedMessage,
} from './letter-format';
import { Letter, LettersService } from './letters.service';

/** A correspondent whose keys live in the test, not in a wallet. */
async function correspondent() {
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
  const sign = async (message: Uint8Array<ArrayBuffer>) =>
    toBase64Url(new Uint8Array(await crypto.subtle.sign('Ed25519', identity.privateKey, message)));
  return {
    publicKey,
    encryptionKey,
    sign,
    account: {
      accountId: 'bob',
      publicKey,
      createdAt: '',
      encryptionKey,
      encryptionKeySignature: await sign(encryptionKeyBinding(publicKey, encryptionKey)),
    },
  };
}

describe('LettersService', () => {
  let letters: LettersService;
  let wallet: WalletService;
  let http: HttpTestingController;
  let bob: Awaited<ReturnType<typeof correspondent>>;

  beforeEach(async () => {
    globalThis.indexedDB = new IDBFactory();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    letters = TestBed.inject(LettersService);
    wallet = TestBed.inject(WalletService);
    http = TestBed.inject(HttpTestingController);
    await wallet.create();
    bob = await correspondent();
  });

  afterEach(() => http.verify());

  /** Sends a letter to Bob, playing the server; returns what the server stored. */
  async function sendToBob(body: string): Promise<Letter> {
    const sending = letters.send(bob.publicKey, body);
    (await nextRequest(http, `/api/accounts/by-key/${bob.publicKey}`)).flush(bob.account);
    const post = await nextRequest(http, '/api/letters');
    const { sentAt, envelope, signature } = post.request.body;
    const hash = toBase64Url(
      await letterHash(letterHeader(wallet.publicKey()!, bob.publicKey, sentAt), envelope),
    );
    const prevHash = GENESIS_PREV_HASH;
    const ledger: LedgerEntry = {
      seq: 1,
      prevHash,
      payloadHash: hash,
      recordedAtMillis: 1_790_000_000_000,
      entryHash: await entryHash(1, prevHash, hash, 1_790_000_000_000),
    };
    const stored: Letter = {
      letterId: 'l1',
      sender: { accountId: 'alice', publicKey: wallet.publicKey()! },
      recipient: { accountId: 'bob', publicKey: bob.publicKey },
      sentAt,
      envelope,
      signature,
      letterHash: hash,
      ledger,
    };
    post.flush(stored);
    await sending;
    return stored;
  }

  async function openWithLedger(letter: Letter, entries: LedgerEntry[]) {
    const opening = letters.open(letter);
    (await nextRequest(http, '/api/ledger/entries?from=1&limit=1000')).flush(entries);
    return opening;
  }

  it('seals, signs and sends a letter that only ciphertext reaches the server', async () => {
    const sending = letters.send(bob.publicKey, 'Dear Bob, the quince tree bloomed.');
    (await nextRequest(http, `/api/accounts/by-key/${bob.publicKey}`)).flush(bob.account);
    const post = await nextRequest(http, '/api/letters');
    const { recipientPublicKey, sentAt, envelope, signature } = post.request.body;

    expect(recipientPublicKey).toBe(bob.publicKey);
    expect(sentAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
    expect(JSON.stringify(post.request.body)).not.toContain('quince');
    const hash = await letterHash(
      letterHeader(wallet.publicKey()!, bob.publicKey, sentAt),
      envelope,
    );
    expect(await verifyEd25519(wallet.publicKey()!, letterSignedMessage(hash), signature)).toBe(
      true,
    );

    post.flush({ letterHash: toBase64Url(hash), ledger: { seq: 7 } });
    await expect(sending).resolves.toMatchObject({ ledger: { seq: 7 } });
  });

  it('refuses to encrypt to a key the recipient did not sign', async () => {
    const mallory = await correspondent();
    const sending = letters.send(bob.publicKey, 'hello');
    (await nextRequest(http, `/api/accounts/by-key/${bob.publicKey}`)).flush({
      ...bob.account,
      encryptionKey: mallory.encryptionKey, // substituted by a malicious server
    });

    await expect(sending).rejects.toThrow(/not signed/);
  });

  it('refuses when the server answers with a different account', async () => {
    const mallory = await correspondent();
    const sending = letters.send(bob.publicKey, 'hello');
    (await nextRequest(http, `/api/accounts/by-key/${bob.publicKey}`)).flush(mallory.account);

    await expect(sending).rejects.toThrow(/different account/);
  });

  it('opens a sent letter and verifies signature, sealing and ledger', async () => {
    const letter = await sendToBob('Dear Bob, sealed by hand.');

    await expect(openWithLedger(letter, [letter.ledger])).resolves.toEqual({
      body: 'Dear Bob, sealed by hand.',
      signatureValid: true,
      decrypted: true,
      ledgerValid: true,
      ledgerProblem: null,
    });
  });

  it('flags a ledger entry that does not commit to the letter', async () => {
    const letter = await sendToBob('hi');
    const otherPayload = toBase64Url(new Uint8Array(32).fill(7));
    const forged: LedgerEntry = {
      ...letter.ledger,
      payloadHash: otherPayload,
      entryHash: await entryHash(
        1,
        GENESIS_PREV_HASH,
        otherPayload,
        letter.ledger.recordedAtMillis,
      ),
    };

    const opened = await openWithLedger({ ...letter, ledger: forged }, [forged]);

    expect(opened.ledgerValid).toBe(false);
    expect(opened.ledgerProblem).toMatch(/doesn't commit/);
  });

  it('flags a broken chain', async () => {
    const letter = await sendToBob('hi');
    const tampered = { ...letter.ledger, recordedAtMillis: letter.ledger.recordedAtMillis + 1 };

    const opened = await openWithLedger(letter, [tampered]);

    expect(opened.ledgerProblem).toMatch(/Chain broken at entry 1/);
  });

  it('flags a signature that is not the sender’s', async () => {
    const letter = await sendToBob('hi');
    const forgedSignature = await bob.sign(letterSignedMessage(fromBase64Url(letter.letterHash)));

    const opened = await openWithLedger({ ...letter, signature: forgedSignature }, [letter.ledger]);

    expect(opened.signatureValid).toBe(false);
    expect(opened.decrypted).toBe(true);
  });

  it('flags ciphertext that was altered after sending', async () => {
    const letter = await sendToBob('hi');
    const bytes = fromBase64Url(letter.envelope.ciphertext);
    bytes[3] ^= 0x40;
    const altered = { ...letter, envelope: { ...letter.envelope, ciphertext: toBase64Url(bytes) } };

    const opened = await openWithLedger(altered, [letter.ledger]);

    expect(opened).toMatchObject({ body: null, decrypted: false, signatureValid: false });
  });

  it('rejects empty and oversized letters before contacting the server', async () => {
    await expect(letters.send(bob.publicKey, '   ')).rejects.toThrow(/1 to/);
    await expect(letters.send(bob.publicKey, 'x'.repeat(10_001))).rejects.toThrow(/1 to/);
  });
});
