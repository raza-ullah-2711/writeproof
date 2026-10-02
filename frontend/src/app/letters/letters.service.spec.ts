import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { IDBFactory } from 'fake-indexeddb';
import { nextRequest } from '../../testing/http';
import { fromBase64Url, toBase64Url } from '../crypto/base64url';
import { verifyEd25519 } from '../crypto/ed25519';
import { HandwritingSample } from '../handwriting/handwriting-sample';
import { WalletService } from '../wallet/wallet.service';
import { GENESIS_PREV_HASH, LedgerEntry, entryHash } from './ledger-verify';
import { sealLetter } from './letter-crypto';
import {
  encryptionKeyBinding,
  handwritingHash,
  letterHash,
  letterHeaderV2,
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

const SIGNATURE: HandwritingSample = {
  format: 'writeproof.handwriting',
  version: 1,
  capturedAt: '2026-10-02T12:00:00.000Z',
  device: 'pen',
  width: 600,
  height: 240,
  strokes: [
    [
      { x: 10, y: 20, t: 0, pressure: 0.4, penDown: true },
      { x: 80, y: 40, t: 300, pressure: 0.6, penDown: true },
      { x: 90, y: 30, t: 420, pressure: 0, penDown: false },
    ],
  ],
};

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

  function ledgerFor(hash: string): Promise<LedgerEntry> {
    return entryHash(1, GENESIS_PREV_HASH, hash, 1_790_000_000_000).then((entry) => ({
      seq: 1,
      prevHash: GENESIS_PREV_HASH,
      payloadHash: hash,
      recordedAtMillis: 1_790_000_000_000,
      entryHash: entry,
    }));
  }

  /** Sends a hand-signed letter to Bob, playing the server; returns what the server stored. */
  async function sendToBob(body: string): Promise<Letter> {
    const sending = letters.send(bob.publicKey, body, SIGNATURE);
    (await nextRequest(http, `/api/accounts/by-key/${bob.publicKey}`)).flush(bob.account);
    const post = await nextRequest(http, '/api/letters');
    const { sentAt, envelope, signature, handwriting } = post.request.body;
    const hwHash = await handwritingHash(handwriting);
    const hash = toBase64Url(
      await letterHash(
        letterHeaderV2(wallet.publicKey()!, bob.publicKey, sentAt, hwHash),
        envelope,
      ),
    );
    const stored: Letter = {
      letterId: 'l1',
      sender: { accountId: 'alice', publicKey: wallet.publicKey()! },
      recipient: { accountId: 'bob', publicKey: bob.publicKey },
      sentAt,
      envelope,
      signature,
      letterHash: hash,
      ledger: await ledgerFor(hash),
      handwritingHash: hwHash,
      handwritingScore: 0.91,
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

  it('seals the signature inside, signs over its hash, and sends the exact JSON', async () => {
    const sending = letters.send(bob.publicKey, 'Dear Bob, the quince tree bloomed.', SIGNATURE);
    (await nextRequest(http, `/api/accounts/by-key/${bob.publicKey}`)).flush(bob.account);
    const post = await nextRequest(http, '/api/letters');
    const { recipientPublicKey, sentAt, envelope, signature, handwriting } = post.request.body;

    expect(recipientPublicKey).toBe(bob.publicKey);
    expect(sentAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
    expect(JSON.stringify(envelope)).not.toContain('quince');
    expect(JSON.parse(handwriting)).toEqual(SIGNATURE);
    const header = letterHeaderV2(
      wallet.publicKey()!,
      bob.publicKey,
      sentAt,
      await handwritingHash(handwriting),
    );
    const hash = await letterHash(header, envelope);
    expect(await verifyEd25519(wallet.publicKey()!, letterSignedMessage(hash), signature)).toBe(
      true,
    );

    post.flush({ letterHash: toBase64Url(hash), ledger: { seq: 7 } });
    await expect(sending).resolves.toMatchObject({ ledger: { seq: 7 } });
  });

  it('refuses to encrypt to a key the recipient did not sign', async () => {
    const mallory = await correspondent();
    const sending = letters.send(bob.publicKey, 'hello', SIGNATURE);
    (await nextRequest(http, `/api/accounts/by-key/${bob.publicKey}`)).flush({
      ...bob.account,
      encryptionKey: mallory.encryptionKey, // substituted by a malicious server
    });

    await expect(sending).rejects.toThrow(/not signed/);
  });

  it('refuses when the server answers with a different account', async () => {
    const mallory = await correspondent();
    const sending = letters.send(bob.publicKey, 'hello', SIGNATURE);
    (await nextRequest(http, `/api/accounts/by-key/${bob.publicKey}`)).flush(mallory.account);

    await expect(sending).rejects.toThrow(/different account/);
  });

  it('opens a hand-signed letter and verifies every part, including the strokes', async () => {
    const letter = await sendToBob('Dear Bob, sealed by hand.');

    await expect(openWithLedger(letter, [letter.ledger])).resolves.toEqual({
      body: 'Dear Bob, sealed by hand.',
      handSigned: true,
      handwriting: SIGNATURE,
      signatureValid: true,
      decrypted: true,
      ledgerValid: true,
      ledgerProblem: null,
    });
  });

  it('does not count a letter as hand-signed if the server swaps the handwriting hash', async () => {
    const letter = await sendToBob('hi');
    const otherHash = await handwritingHash('{"format":"writeproof.handwriting"}');

    const opened = await openWithLedger({ ...letter, handwritingHash: otherHash }, [letter.ledger]);

    expect(opened).toMatchObject({ handSigned: false, handwriting: null, signatureValid: false });
  });

  it('opens a v1 letter (sent before hand-signing) and says so', async () => {
    const sentAt = '2026-10-01T09:00:00.000Z';
    const sealed = await sealLetter({
      body: 'from before',
      senderKey: bob.publicKey,
      recipientKey: wallet.publicKey()!,
      senderEncryptionKey: bob.encryptionKey,
      recipientEncryptionKey: wallet.encryptionPublicKey()!,
      sentAt,
    });
    const hash = toBase64Url(sealed.hash);
    const letter: Letter = {
      letterId: 'old',
      sender: { accountId: 'bob', publicKey: bob.publicKey },
      recipient: { accountId: 'alice', publicKey: wallet.publicKey()! },
      sentAt,
      envelope: sealed.envelope,
      signature: await bob.sign(letterSignedMessage(sealed.hash)),
      letterHash: hash,
      ledger: await ledgerFor(hash),
      handwritingHash: null,
      handwritingScore: null,
    };

    await expect(openWithLedger(letter, [letter.ledger])).resolves.toMatchObject({
      body: 'from before',
      handSigned: null,
      handwriting: null,
      signatureValid: true,
      decrypted: true,
      ledgerValid: true,
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

  it('flags a wallet signature that is not the sender’s', async () => {
    const letter = await sendToBob('hi');
    const forgedSignature = await bob.sign(letterSignedMessage(fromBase64Url(letter.letterHash)));

    const opened = await openWithLedger({ ...letter, signature: forgedSignature }, [letter.ledger]);

    expect(opened.signatureValid).toBe(false);
    expect(opened.handSigned).toBe(false);
    expect(opened.decrypted).toBe(true);
  });

  it('flags ciphertext that was altered after sending', async () => {
    const letter = await sendToBob('hi');
    const bytes = fromBase64Url(letter.envelope.ciphertext);
    bytes[3] ^= 0x40;
    const altered = { ...letter, envelope: { ...letter.envelope, ciphertext: toBase64Url(bytes) } };

    const opened = await openWithLedger(altered, [letter.ledger]);

    expect(opened).toMatchObject({
      body: null,
      decrypted: false,
      signatureValid: false,
      handSigned: false,
    });
  });

  it('rejects empty and oversized letters before contacting the server', async () => {
    await expect(letters.send(bob.publicKey, '   ', SIGNATURE)).rejects.toThrow(/1 to/);
    await expect(letters.send(bob.publicKey, 'x'.repeat(10_001), SIGNATURE)).rejects.toThrow(
      /1 to/,
    );
  });
});
