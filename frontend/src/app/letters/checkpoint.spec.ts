import { fromBase64Url, toBase64Url } from '../crypto/base64url';
import { Checkpoint, checkpointMessage, verifyCheckpoint } from './checkpoint';

const unhex = (s: string) => Uint8Array.from(s.match(/../g) ?? [], (h) => parseInt(h, 16));

/** RFC 8032 test 1 key, signing the shared vector in backend LedgerSignerTest. */
const LEDGER_KEY = toBase64Url(
  unhex('d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a'),
);
const VECTOR: Checkpoint = {
  size: 7,
  root: 'NWAZGAMChESyMgGKwEf9tWHAnCOnpodsheCLXk1I6fM',
  timestampMillis: 1767225600000,
  signature:
    'nkGhr4HnxlqyXyEMStJQm7mgedynAioAhUduKiROM9RvD5frpThdsxJXPsIJbZDrwax4imdDwakhhYXpHT4XAw',
};

describe('ledger checkpoints', () => {
  it('builds the signed message the backend signs', () => {
    expect(new TextDecoder().decode(checkpointMessage(VECTOR))).toBe(
      'writeproof/checkpoint/v1\n7\nNWAZGAMChESyMgGKwEf9tWHAnCOnpodsheCLXk1I6fM\n1767225600000',
    );
  });

  it('verifies the shared vector', async () => {
    expect(await verifyCheckpoint(LEDGER_KEY, VECTOR)).toBe(true);
  });

  it('rejects any change to what was signed', async () => {
    expect(await verifyCheckpoint(LEDGER_KEY, { ...VECTOR, size: 8 })).toBe(false);
    expect(await verifyCheckpoint(LEDGER_KEY, { ...VECTOR, timestampMillis: 0 })).toBe(false);
    const root = fromBase64Url(VECTOR.root);
    root[0] ^= 1;
    expect(await verifyCheckpoint(LEDGER_KEY, { ...VECTOR, root: toBase64Url(root) })).toBe(false);
  });

  it('rejects malformed checkpoints and other keys', async () => {
    expect(await verifyCheckpoint(LEDGER_KEY, { ...VECTOR, root: 'AAAA' })).toBe(false);
    expect(await verifyCheckpoint(LEDGER_KEY, { ...VECTOR, size: -1 })).toBe(false);
    expect(await verifyCheckpoint(toBase64Url(new Uint8Array(32).fill(9)), VECTOR)).toBe(false);
  });
});
