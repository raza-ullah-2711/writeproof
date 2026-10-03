import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { fromBase64Url, toBase64Url } from '../crypto/base64url';
import { Checkpoint, checkpointMessage } from './checkpoint';
import { KeyRotation, keyRotationMessage } from './key-rotation';
import { LedgerTrustStore } from './ledger-trust';
import { LedgerVerifier } from './ledger-verifier';
import { GENESIS_PREV_HASH, LedgerEntry, entryHash } from './ledger-verify';
import { sha256 } from './letter-format';
import { leafHash, nodeHash } from './merkle';
import { AnchorProof, PUBLIC_LOG, PublicLog, ledgerKeyPem, pae } from './rekor';

const NOT_FOUND = Symbol('404');

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
  /** What /api/ledger/anchor/latest answers; 404 while null. */
  anchor: AnchorProof | null = null;

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
      case '/api/ledger/anchor/latest':
        return this.anchor ?? NOT_FOUND;
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
        const body = await server.answer(req.request.urlWithParams);
        if (body === NOT_FOUND) {
          req.flush(null, { status: 404, statusText: 'Not Found' });
        } else {
          req.flush(body as object);
        }
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

interface TestLog {
  keys: CryptoKeyPair;
  config: PublicLog;
}

async function newLog(): Promise<TestLog> {
  const keys = (await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, [
    'sign',
    'verify',
  ])) as CryptoKeyPair;
  const spki = new Uint8Array(await crypto.subtle.exportKey('spki', keys.publicKey));
  return { keys, config: { url: 'https://log.test', key: b64(spki), graceMillis: 60_000 } };
}

const b64 = (b: Uint8Array) => btoa(String.fromCharCode(...b));
const hex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');

/** WebCrypto signs ECDSA as r ‖ s; the log writes DER, as Rekor does. */
function der(raw: Uint8Array): Uint8Array {
  const int = (x: Uint8Array) => {
    let i = 0;
    while (i < x.length - 1 && x[i] === 0) i++;
    const v = x[i] & 0x80 ? Uint8Array.of(0, ...x.slice(i)) : x.slice(i);
    return Uint8Array.of(0x02, v.length, ...v);
  };
  const r = int(raw.slice(0, 32));
  const s = int(raw.slice(32));
  return Uint8Array.of(0x30, r.length + s.length, ...r, ...s);
}

/** An anchor of `c` signed by `ledgerKey`, in a one-entry log whose tree head `log` signs. */
async function anchorFor(
  log: TestLog,
  ledgerKey: TestKey,
  c: Pick<Checkpoint, 'size' | 'root'>,
): Promise<AnchorProof> {
  const unsigned = { size: c.size, root: c.root, timestampMillis: 3 };
  const payload = checkpointMessage(unsigned);
  const dsse = new Uint8Array(
    await crypto.subtle.sign(
      'Ed25519',
      ledgerKey.privateKey,
      pae('application/vnd.writeproof.checkpoint+text', payload),
    ),
  );
  const body = {
    apiVersion: '0.0.1',
    kind: 'dsse',
    spec: {
      payloadHash: { algorithm: 'sha256', value: hex(await sha256(payload)) },
      signatures: [{ signature: b64(dsse), verifier: btoa(ledgerKeyPem(ledgerKey.publicKey)) }],
    },
  };
  const bodyBytes = new TextEncoder().encode(JSON.stringify(body));
  const leaf = await leafHash(bodyBytes);
  const text = `log.test - 1\n1\n${b64(leaf)}\n`;
  const raw = new Uint8Array(
    await crypto.subtle.sign(
      { name: 'ECDSA', hash: 'SHA-256' },
      log.keys.privateKey,
      new TextEncoder().encode(text),
    ),
  );
  return {
    checkpoint: { ...unsigned, signature: await sign(ledgerKey, payload) },
    logUrl: log.config.url,
    entry: {
      uuid: 'ab'.repeat(8) + hex(leaf),
      logIndex: 0,
      body: b64(bodyBytes),
      proof: {
        logIndex: 0,
        treeSize: 1,
        rootHash: hex(leaf),
        hashes: [],
        checkpoint: `${text}\n— log.test ${b64(Uint8Array.of(0, 0, 0, 0, ...der(raw)))}\n`,
      },
    },
  };
}

describe('LedgerVerifier and the public log', () => {
  let http: HttpTestingController;
  let verifier: LedgerVerifier;
  let trust: LedgerTrustStore;
  let key: TestKey;
  let log: TestLog;
  let server: FakeServer;
  let now: number;

  beforeEach(async () => {
    localStorage.clear();
    [key, log] = await Promise.all([newKey(), newLog()]);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PUBLIC_LOG, useValue: log.config },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    verifier = TestBed.inject(LedgerVerifier);
    trust = TestBed.inject(LedgerTrustStore);
    server = await FakeServer.create(key);
    now = 1_767_225_600_000;
    vi.spyOn(Date, 'now').mockImplementation(() => now);
  });

  afterEach(() => vi.restoreAllMocks());

  async function check(seq: number) {
    const e = server.entries[seq - 1];
    let done = false;
    const result = verifier
      .verifyEntry({ seq, entryHash: e.entryHash }, e.payloadHash)
      .finally(() => (done = true));
    while (!done) {
      await new Promise((resolve) => setTimeout(resolve, 0));
      for (const req of http.match(() => true)) {
        const body = await server.answer(req.request.urlWithParams);
        if (body === NOT_FOUND) {
          req.flush(null, { status: 404, statusText: 'Not Found' });
        } else {
          req.flush(body as object);
        }
      }
    }
    return result;
  }

  it('accepts a fresh view, then requires it to be anchored once the grace period is over', async () => {
    expect((await check(1)).problem).toBeNull();

    now += 2 * 60_000;
    expect((await check(1)).problem).toMatch(/still not anchored in the public log/);

    server.anchor = await anchorFor(log, key, { size: 1, root: await server.root(1) });
    expect((await check(1)).problem).toBeNull();
    expect(trust.anchoring()).toEqual({ pending: null, anchoredSize: 1 });
  });

  it('catches a publicly anchored history that differs from what it was shown', async () => {
    await check(1);
    now += 2 * 60_000;
    const otherRoot = toBase64Url(new Uint8Array(32).fill(9));
    server.anchor = await anchorFor(log, key, { size: 2, root: otherRoot });

    expect((await check(1)).problem).toMatch(/shown a different history/);
  });

  it('rejects an anchor that the public log did not sign', async () => {
    await check(1);
    now += 2 * 60_000;
    server.anchor = await anchorFor(await newLog(), key, { size: 1, root: await server.root(1) });

    expect((await check(1)).problem).toMatch(/not signed by the log's key/);
  });
});
