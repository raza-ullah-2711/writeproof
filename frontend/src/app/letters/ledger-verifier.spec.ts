import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { fromBase64Url, toBase64Url } from '../crypto/base64url';
import { Checkpoint, checkpointMessage } from './checkpoint';
import { KeyRotation, keyRotationMessage } from './key-rotation';
import { LedgerTrustStore } from './ledger-trust';
import { LedgerVerifier } from './ledger-verifier';
import { GENESIS_PREV_HASH, LedgerEntry, entryHash } from './ledger-verify';
import { leafHash, nodeHash } from './merkle';

interface TestKey {
  publicKey: string;
  privateKey: CryptoKey;
}

async function newKey(): Promise<TestKey> {
  const pair = (await crypto.subtle.generateKey({ name: 'Ed25519' }, false, [
    'sign',
    'verify',
  ])) as CryptoKeyPair;
  const raw = new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey));
  return { publicKey: toBase64Url(raw), privateKey: pair.privateKey };
}

async function sign(key: TestKey, message: Uint8Array<ArrayBuffer>): Promise<string> {
  return toBase64Url(new Uint8Array(await crypto.subtle.sign('Ed25519', key.privateKey, message)));
}

async function entry(seq: number, prevHash: string): Promise<LedgerEntry> {
  const payloadHash = toBase64Url(crypto.getRandomValues(new Uint8Array(32)));
  const recordedAtMillis = 1767225600000 + seq;
  return {
    seq,
    prevHash,
    payloadHash,
    recordedAtMillis,
    entryHash: await entryHash(seq, prevHash, payloadHash, recordedAtMillis),
  };
}

/**
 * Plays a server whose ledger has one or two entries, small enough to write the Merkle proofs by
 * hand: with two leaves, each proves its inclusion with the other, and the tree of one extends to
 * the tree of two with the second leaf.
 */
class FakeServer {
  size = 1;
  rotations: KeyRotation[] = [];
  /** Signs checkpoints; defaults to `key`. */
  signer?: TestKey;

  private constructor(
    public key: TestKey,
    readonly entries: LedgerEntry[],
    readonly leaves: Uint8Array<ArrayBuffer>[],
  ) {}

  static async create(key: TestKey): Promise<FakeServer> {
    const first = await entry(1, GENESIS_PREV_HASH);
    const second = await entry(2, first.entryHash);
    const leaves = await Promise.all(
      [first, second].map((e) => leafHash(fromBase64Url(e.entryHash))),
    );
    return new FakeServer(key, [first, second], leaves);
  }

  async root(size: number): Promise<string> {
    return toBase64Url(
      size === 1 ? this.leaves[0] : await nodeHash(this.leaves[0], this.leaves[1]),
    );
  }

  async rotateTo(next: TestKey, root?: string): Promise<void> {
    const unsigned = {
      oldKey: this.key.publicKey,
      newKey: next.publicKey,
      size: this.size,
      root: root ?? (await this.root(this.size)),
      timestampMillis: 1,
    };
    this.rotations.push({
      ...unsigned,
      signature: await sign(this.key, keyRotationMessage(unsigned)),
    });
    this.key = next;
  }

  async answer(url: string): Promise<unknown> {
    const { pathname, searchParams } = new URL(url, 'http://localhost');
    switch (pathname) {
      case '/api/ledger/key':
        return { publicKey: this.key.publicKey, rotations: this.rotations };
      case '/api/ledger/proof/inclusion': {
        const seq = Number(searchParams.get('seq'));
        const unsigned = { size: this.size, root: await this.root(this.size), timestampMillis: 2 };
        const checkpoint: Checkpoint = {
          ...unsigned,
          signature: await sign(this.signer ?? this.key, checkpointMessage(unsigned)),
        };
        const proof = this.size === 1 ? [] : [toBase64Url(this.leaves[2 - seq])];
        return { checkpoint, entry: this.entries[seq - 1], proof };
      }
      case '/api/ledger/proof/consistency':
        return { from: 1, to: 2, proof: [toBase64Url(this.leaves[1])] };
      default:
        throw new Error(`Unexpected request ${url}`);
    }
  }
}

describe('LedgerVerifier and key rotation', () => {
  let http: HttpTestingController;
  let verifier: LedgerVerifier;
  let trust: LedgerTrustStore;
  let a: TestKey;
  let b: TestKey;
  let server: FakeServer;

  beforeEach(async () => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    verifier = TestBed.inject(LedgerVerifier);
    trust = TestBed.inject(LedgerTrustStore);
    [a, b] = await Promise.all([newKey(), newKey()]);
    server = await FakeServer.create(a);
  });

  /** Verifies entry `seq` while the fake server answers every request it makes. */
  async function check(seq: number) {
    const e = server.entries[seq - 1];
    let done = false;
    const result = verifier
      .verifyEntry({ seq, entryHash: e.entryHash }, e.payloadHash)
      .finally(() => (done = true));
    while (!done) {
      await new Promise((resolve) => setTimeout(resolve, 0));
      for (const req of http.match(() => true)) {
        req.flush((await server.answer(req.request.urlWithParams)) as object);
      }
    }
    return result;
  }

  it('follows a key the pinned key handed over to, and pins the new key', async () => {
    expect(await check(1)).toEqual({ problem: null, size: 1 });

    await server.rotateTo(b);
    server.size = 2;

    expect(await check(2)).toEqual({ problem: null, size: 2 });
    expect(trust.load()).toEqual({ publicKey: b.publicKey, size: 2, root: await server.root(2) });
  });

  it('still refuses a key change with no handover', async () => {
    await check(1);
    server.key = b;

    expect((await check(1)).problem).toMatch(/ledger key changed/);
    expect(trust.load()?.publicKey).toBe(a.publicKey);
  });

  it('refuses a handover at a checkpoint that rewrites what this browser saw', async () => {
    await check(1);
    await server.rotateTo(b, toBase64Url(new Uint8Array(32).fill(7)));

    expect((await check(1)).problem).toMatch(/rewritten/);
  });

  it('refuses checkpoints the retired key signs after the handover', async () => {
    await check(1);
    await server.rotateTo(b);
    server.signer = a;

    expect((await check(1)).problem).toMatch(/not signed by the ledger key/);
  });
});
