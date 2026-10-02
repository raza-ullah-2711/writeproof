import { toBase64Url } from '../crypto/base64url';
import { GENESIS_PREV_HASH, LedgerEntry, entryHash, verifyChain } from './ledger-verify';

const bytes = (from: number, n: number) =>
  toBase64Url(Uint8Array.from({ length: n }, (_, i) => (from + i) & 0xff));

async function chain(length: number): Promise<LedgerEntry[]> {
  const entries: LedgerEntry[] = [];
  let prev = GENESIS_PREV_HASH;
  for (let seq = 1; seq <= length; seq++) {
    const payloadHash = bytes(seq, 32);
    const recordedAtMillis = 1_790_000_000_000 + seq;
    const hash = await entryHash(seq, prev, payloadHash, recordedAtMillis);
    entries.push({ seq, prevHash: prev, payloadHash, recordedAtMillis, entryHash: hash });
    prev = hash;
  }
  return entries;
}

describe('ledger verification', () => {
  it('matches the backend entry hash (shared vector with FormatVectorsTest)', async () => {
    expect(await entryHash(3, bytes(0, 32), bytes(32, 32), 1_790_000_000_123)).toBe(
      'YkX8efNwvw09CuvGvt9Cs6Lllmff-CZT-urM_XkFWlY',
    );
  });

  it('accepts an intact chain', async () => {
    expect(await verifyChain(await chain(5))).toEqual({ intact: true });
  });

  it('detects an entry whose contents were changed', async () => {
    const entries = await chain(5);
    entries[2] = { ...entries[2], payloadHash: bytes(99, 32) };

    expect(await verifyChain(entries)).toMatchObject({ intact: false, brokenAt: 3 });
  });

  it('detects an entry that was rewritten with a fresh hash (the next link breaks)', async () => {
    const entries = await chain(5);
    const payloadHash = bytes(99, 32);
    const rehashed = await entryHash(
      3,
      entries[2].prevHash,
      payloadHash,
      entries[2].recordedAtMillis,
    );
    entries[2] = { ...entries[2], payloadHash, entryHash: rehashed };

    expect(await verifyChain(entries)).toMatchObject({ intact: false, brokenAt: 4 });
  });

  it('detects a removed entry', async () => {
    const entries = await chain(5);
    entries.splice(1, 1);

    expect(await verifyChain(entries)).toMatchObject({ intact: false, brokenAt: 2 });
  });
});
