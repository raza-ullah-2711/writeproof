import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { IDBFactory } from 'fake-indexeddb';
import { nextRequest } from '../../testing/http';
import { TestMerkleTree } from '../../testing/merkle-tree';
import { fromBase64Url, toBase64Url } from '../crypto/base64url';
import { verifyEd25519 } from '../crypto/ed25519';
import { HandwritingSample } from '../handwriting/handwriting-sample';
import { WalletService } from '../wallet/wallet.service';
import { Checkpoint, checkpointMessage } from './checkpoint';
import { GENESIS_PREV_HASH, LedgerEntry, entryHash } from './ledger-verify';
import { sealLetter } from './letter-crypto';
import {
  encryptionKeyBinding,
  handwritingHash,
  letterHash,
  letterHeaderV2,
  letterHeaderV3,
  letterSignedMessage,
} from './letter-format';
import { Letter, LettersService, checkThread } from './letters.service';

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

async function ledgerKey() {
  const pair = (await crypto.subtle.generateKey({ name: 'Ed25519' }, true, [
    'sign',
    'verify',
  ])) as CryptoKeyPair;
  return {
    publicKey: toBase64Url(new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey))),
    sign: async (message: Uint8Array<ArrayBuffer>) =>
      toBase64Url(new Uint8Array(await crypto.subtle.sign('Ed25519', pair.privateKey, message))),
  };
}

/** Plays the server's ledger: a hash chain, signed checkpoints and Merkle proofs over it. */
class FakeLedger {
  readonly entries: LedgerEntry[] = [];
  readonly requests: string[] = [];
  /** Lets a test make the server misbehave in one response. */
  tamper: {
    checkpoint?: (c: Checkpoint) => Promise<Checkpoint>;
    entry?: (e: LedgerEntry) => Promise<LedgerEntry>;
  } = {};

  private constructor(public key: Awaited<ReturnType<typeof ledgerKey>>) {}

  static async create(): Promise<FakeLedger> {
    return new FakeLedger(await ledgerKey());
  }

  async append(payloadHash: string): Promise<LedgerEntry> {
    const seq = this.entries.length + 1;
    const prevHash = this.entries.at(-1)?.entryHash ?? GENESIS_PREV_HASH;
    const recordedAtMillis = 1_790_000_000_000 + seq;
    const entry = {
      seq,
      prevHash,
      payloadHash,
      recordedAtMillis,
      entryHash: await entryHash(seq, prevHash, payloadHash, recordedAtMillis),
    };
    this.entries.push(entry);
    return entry;
  }

  async appendFiller(n: number): Promise<void> {
    for (let i = 0; i < n; i++) {
      await this.append(toBase64Url(crypto.getRandomValues(new Uint8Array(32))));
    }
  }

  /** Replaces entry #seq with a different, self-consistent one: history rewritten. */
  async rewrite(seq: number): Promise<void> {
    const old = this.entries[seq - 1];
    const payloadHash = toBase64Url(new Uint8Array(32).fill(seq));
    this.entries[seq - 1] = {
      ...old,
      payloadHash,
      entryHash: await entryHash(seq, old.prevHash, payloadHash, old.recordedAtMillis),
    };
  }

  async handle(req: TestRequest): Promise<void> {
    this.requests.push(req.request.urlWithParams);
    const tree = await TestMerkleTree.of(this.entries.map((e) => fromBase64Url(e.entryHash)));
    const size = this.entries.length;
    const param = (name: string) => Number(req.request.params.get(name));
    switch (req.request.url) {
      case '/api/ledger/key':
        return req.flush({ publicKey: this.key.publicKey });
      case '/api/ledger/proof/inclusion': {
        const seq = param('seq');
        const unsigned = { size, root: toBase64Url(await tree.root()), timestampMillis: 1 };
        let checkpoint = {
          ...unsigned,
          signature: await this.key.sign(checkpointMessage(unsigned)),
        };
        checkpoint = (await this.tamper.checkpoint?.(checkpoint)) ?? checkpoint;
        const entry = this.entries[seq - 1];
        return req.flush({
          checkpoint,
          entry: (await this.tamper.entry?.(entry)) ?? entry,
          proof: (await tree.inclusionProof(seq - 1, size)).map(toBase64Url),
        });
      }
      case '/api/ledger/proof/consistency': {
        const proof = await tree.consistencyProof(param('from'), param('to'));
        return req.flush({ from: param('from'), to: param('to'), proof: proof.map(toBase64Url) });
      }
      default:
        throw new Error(`Unexpected ledger request ${req.request.url}`);
    }
  }
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
  let ledger: FakeLedger;

  beforeEach(async () => {
    globalThis.indexedDB = new IDBFactory();
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    letters = TestBed.inject(LettersService);
    wallet = TestBed.inject(WalletService);
    http = TestBed.inject(HttpTestingController);
    await wallet.create();
    bob = await correspondent();
    ledger = await FakeLedger.create();
  });

  afterEach(() => http.verify());

  /** Records the letter as entry #3 of 5, as the server would. */
  async function ledgerFor(hash: string): Promise<LedgerEntry> {
    await ledger.appendFiller(2);
    const entry = await ledger.append(hash);
    await ledger.appendFiller(2);
    return entry;
  }

  /** Sends a hand-signed letter to Bob, playing the server; returns what the server stored. */
  async function sendToBob(body: string, inReplyTo?: Letter): Promise<Letter> {
    const sending = letters.send(bob.publicKey, body, SIGNATURE, inReplyTo);
    (await nextRequest(http, `/api/accounts/by-key/${bob.publicKey}`)).flush(bob.account);
    const post = await nextRequest(http, '/api/letters');
    const { sentAt, envelope, signature, handwriting } = post.request.body;
    expect(post.request.body.inReplyTo).toBe(inReplyTo?.letterHash);
    const hwHash = await handwritingHash(handwriting);
    const header = inReplyTo
      ? letterHeaderV3(wallet.publicKey()!, bob.publicKey, sentAt, hwHash, inReplyTo.letterHash)
      : letterHeaderV2(wallet.publicKey()!, bob.publicKey, sentAt, hwHash);
    const hash = toBase64Url(await letterHash(header, envelope));
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
      inReplyTo: inReplyTo?.letterHash ?? null,
      threadId: inReplyTo?.threadId ?? hash,
    };
    post.flush(stored);
    await sending;
    return stored;
  }

  /** Opens a letter while the fake ledger answers every ledger request it makes. */
  async function openWithLedger(letter: Letter) {
    let done = false;
    const opening = letters.open(letter).finally(() => (done = true));
    while (!done) {
      for (const req of http.match((r) => r.url.startsWith('/api/ledger/'))) {
        await ledger.handle(req);
      }
      await new Promise((resolve) => setTimeout(resolve));
    }
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

    await expect(openWithLedger(letter)).resolves.toEqual({
      body: 'Dear Bob, sealed by hand.',
      handSigned: true,
      handwriting: SIGNATURE,
      signatureValid: true,
      decrypted: true,
      ledgerValid: true,
      ledgerProblem: null,
      ledgerCheckpointSize: 5,
    });
    expect(ledger.requests).toEqual(['/api/ledger/key', '/api/ledger/proof/inclusion?seq=3']);
  });

  it('does not count a letter as hand-signed if the server swaps the handwriting hash', async () => {
    const letter = await sendToBob('hi');
    const otherHash = await handwritingHash('{"format":"writeproof.handwriting"}');

    const opened = await openWithLedger({ ...letter, handwritingHash: otherHash });

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
      inReplyTo: null,
      threadId: hash,
    };

    await expect(openWithLedger(letter)).resolves.toMatchObject({
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
    await ledger.rewrite(letter.ledger.seq);

    const opened = await openWithLedger(letter);

    expect(opened.ledgerValid).toBe(false);
    expect(opened.ledgerProblem).toMatch(/doesn't commit/);
    expect(opened.ledgerCheckpointSize).toBeNull();
  });

  it('flags an entry whose hash does not match its contents', async () => {
    const letter = await sendToBob('hi');
    ledger.tamper.entry = async (e) => ({ ...e, recordedAtMillis: e.recordedAtMillis + 1 });

    expect((await openWithLedger(letter)).ledgerProblem).toMatch(/malformed entry/);
  });

  it('flags a checkpoint not signed by the ledger key', async () => {
    const letter = await sendToBob('hi');
    const impostor = await ledgerKey();
    ledger.tamper.checkpoint = async (c) => ({
      ...c,
      signature: await impostor.sign(checkpointMessage(c)),
    });

    expect((await openWithLedger(letter)).ledgerProblem).toMatch(/not signed by the ledger key/);
  });

  it('flags an entry the signed checkpoint does not contain', async () => {
    const letter = await sendToBob('hi');
    // A validly signed checkpoint, but over some other tree than the one the proof is for.
    const other = await TestMerkleTree.of(ledger.entries.map((_, i) => Uint8Array.of(i)));
    const root = toBase64Url(await other.root());
    ledger.tamper.checkpoint = async (c) => {
      const unsigned = { size: c.size, root, timestampMillis: c.timestampMillis };
      return { ...unsigned, signature: await ledger.key.sign(checkpointMessage(unsigned)) };
    };

    expect((await openWithLedger(letter)).ledgerProblem).toMatch(/not in the signed checkpoint/);
  });

  it('pins the ledger key on first use and refuses a different one later', async () => {
    const letter = await sendToBob('hi');
    expect((await openWithLedger(letter)).ledgerValid).toBe(true);

    ledger.key = await ledgerKey();
    const opened = await openWithLedger(letter);

    expect(opened.ledgerValid).toBe(false);
    expect(opened.ledgerProblem).toMatch(/key changed/);
  });

  it('checks that the ledger only grew since the last checkpoint it saw', async () => {
    const letter = await sendToBob('hi');
    await openWithLedger(letter);
    await ledger.appendFiller(4);

    const opened = await openWithLedger(letter);

    expect(opened).toMatchObject({ ledgerValid: true, ledgerCheckpointSize: 9 });
    expect(ledger.requests).toContain('/api/ledger/proof/consistency?from=5&to=9');
  });

  it('catches history rewritten between two checks', async () => {
    const letter = await sendToBob('hi');
    await openWithLedger(letter);
    await ledger.rewrite(1);
    await ledger.appendFiller(1);

    const opened = await openWithLedger(letter);

    expect(opened.ledgerValid).toBe(false);
    expect(opened.ledgerProblem).toMatch(/rewritten/);
  });

  it('catches a ledger that shrank', async () => {
    const letter = await sendToBob('hi');
    await openWithLedger(letter);
    ledger.entries.pop();

    expect((await openWithLedger(letter)).ledgerProblem).toMatch(/shrank from 5 to 4/);
  });

  it('reports a ledger that cannot be reached', async () => {
    const letter = await sendToBob('hi');
    const opening = letters.open(letter);
    (await nextRequest(http, '/api/ledger/key')).flush('down', {
      status: 503,
      statusText: 'Unavailable',
    });

    expect((await opening).ledgerProblem).toMatch(/could not be fetched/);
  });

  it('flags a wallet signature that is not the sender’s', async () => {
    const letter = await sendToBob('hi');
    const forgedSignature = await bob.sign(letterSignedMessage(fromBase64Url(letter.letterHash)));

    const opened = await openWithLedger({ ...letter, signature: forgedSignature });

    expect(opened.signatureValid).toBe(false);
    expect(opened.handSigned).toBe(false);
    expect(opened.decrypted).toBe(true);
  });

  it('flags ciphertext that was altered after sending', async () => {
    const letter = await sendToBob('hi');
    const bytes = fromBase64Url(letter.envelope.ciphertext);
    bytes[3] ^= 0x40;
    const altered = { ...letter, envelope: { ...letter.envelope, ciphertext: toBase64Url(bytes) } };

    const opened = await openWithLedger(altered);

    expect(opened).toMatchObject({
      body: null,
      decrypted: false,
      signatureValid: false,
      handSigned: false,
    });
  });

  it('sends a reply whose signature commits to the letter it answers', async () => {
    const first = await sendToBob('Dear Bob,');
    const reply = await sendToBob('And another thing.', first);

    const opened = await openWithLedger(reply);
    expect(opened).toMatchObject({
      body: 'And another thing.',
      signatureValid: true,
      handSigned: true,
    });

    // A server that re-threads the reply (points it at another letter) breaks the signature.
    const rethreaded = await openWithLedger({ ...reply, inReplyTo: reply.letterHash });
    expect(rethreaded).toMatchObject({ signatureValid: false, decrypted: false });
    // ...and so does one that strips the link to pass it off as a fresh letter.
    expect((await openWithLedger({ ...reply, inReplyTo: null })).signatureValid).toBe(false);
  });

  it('sends a reply only to the other person in the conversation', async () => {
    const first = await sendToBob('Dear Bob,');
    const carol = await correspondent();

    await expect(letters.send(carol.publicKey, 'psst', SIGNATURE, first)).rejects.toThrow(
      /other person in the conversation/,
    );
  });

  it('rejects empty and oversized letters before contacting the server', async () => {
    await expect(letters.send(bob.publicKey, '   ', SIGNATURE)).rejects.toThrow(/1 to/);
    await expect(letters.send(bob.publicKey, 'x'.repeat(10_001), SIGNATURE)).rejects.toThrow(
      /1 to/,
    );
  });
});

describe('checkThread', () => {
  const party = (k: string) => ({ accountId: k, publicKey: k });
  const letter = (hash: string, inReplyTo: string | null, from = 'A', to = 'B', seq = 1): Letter =>
    ({
      letterHash: hash,
      inReplyTo,
      threadId: 'h1',
      sender: party(from),
      recipient: party(to),
      ledger: { seq },
    }) as unknown as Letter;

  it('accepts letters that each answer an earlier one, between the same two people', () => {
    expect(
      checkThread('h1', [
        letter('h1', null),
        letter('h2', 'h1', 'B', 'A'),
        letter('h3', 'h1', 'A', 'B'),
        letter('h4', 'h3', 'B', 'A'),
      ]),
    ).toBeNull();
  });

  it('rejects a thread that does not start with its namesake, or links outside itself', () => {
    expect(checkThread('h1', [])).toMatch(/empty/);
    expect(checkThread('h1', [letter('h2', null)])).toMatch(/doesn't start/);
    expect(checkThread('h1', [letter('h1', 'h0')])).toMatch(/doesn't start/);
    expect(checkThread('h1', [letter('h1', null), letter('h3', 'h2', 'B', 'A', 7)])).toMatch(
      /#7 doesn't answer an earlier letter/,
    );
    expect(checkThread('h1', [letter('h1', null), letter('h2', null, 'B', 'A', 8)])).toMatch(
      /#8 doesn't answer/,
    );
    expect(checkThread('h1', [letter('h1', null), letter('h2', 'h1', 'C', 'A')])).toMatch(
      /different people/,
    );
  });
});
