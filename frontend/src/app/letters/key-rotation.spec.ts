import { toBase64Url } from '../crypto/base64url';
import {
  KeyRotation,
  followRotations,
  keyRotationMessage,
  verifyKeyRotation,
} from './key-rotation';

const unhex = (s: string) => Uint8Array.from(s.match(/../g) ?? [], (h) => parseInt(h, 16));

/** RFC 8032 tests 1 and 2: the shared vector in backend KeyRotationTest, signed by test 1's key. */
const VECTOR: KeyRotation = {
  oldKey: toBase64Url(unhex('d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a')),
  newKey: toBase64Url(unhex('3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c')),
  size: 7,
  root: 'NWAZGAMChESyMgGKwEf9tWHAnCOnpodsheCLXk1I6fM',
  timestampMillis: 1767225600000,
  signature:
    'KmP5kUVS-0UV-Nf0O1SANHOhJlXW_DUxkD4WHxPAwZ02jN9KMoDCdn_DRc83uNPx5iNbiuzqbV_ppbGYBFeADA',
};

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

async function rotate(from: TestKey, to: string, size: number): Promise<KeyRotation> {
  const unsigned = {
    oldKey: from.publicKey,
    newKey: to,
    size,
    root: VECTOR.root,
    timestampMillis: 1,
  };
  const signature = await crypto.subtle.sign(
    'Ed25519',
    from.privateKey,
    keyRotationMessage(unsigned),
  );
  return { ...unsigned, signature: toBase64Url(new Uint8Array(signature)) };
}

describe('ledger key rotations', () => {
  it('builds the signed message the backend signs and verifies the shared vector', async () => {
    expect(new TextDecoder().decode(keyRotationMessage(VECTOR))).toBe(
      'writeproof/key-rotation/v1\n11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo\n' +
        'PUAXw-hDiVqStwqnTRt-vJyYLM8uxJaMwM1V8Sr0Zgw\n7\n' +
        'NWAZGAMChESyMgGKwEf9tWHAnCOnpodsheCLXk1I6fM\n1767225600000',
    );
    expect(await verifyKeyRotation(VECTOR)).toBe(true);
  });

  it('rejects any change to what was signed, and malformed rotations', async () => {
    expect(await verifyKeyRotation({ ...VECTOR, size: 8 })).toBe(false);
    expect(await verifyKeyRotation({ ...VECTOR, newKey: VECTOR.oldKey })).toBe(false);
    expect(await verifyKeyRotation({ ...VECTOR, timestampMillis: 0 })).toBe(false);
    expect(await verifyKeyRotation({ ...VECTOR, root: 'AAAA' })).toBe(false);
    expect(await verifyKeyRotation({ ...VECTOR, size: -1 })).toBe(false);
  });

  it('follows a chain of rotations from any key in it', async () => {
    const [a, b, c] = await Promise.all([newKey(), newKey(), newKey()]);
    const ab = await rotate(a, b.publicKey, 5);
    const bc = await rotate(b, c.publicKey, 9);

    expect(await followRotations(a.publicKey, c.publicKey, [ab, bc])).toEqual({
      rotations: [ab, bc],
    });
    expect(await followRotations(b.publicKey, c.publicKey, [ab, bc])).toEqual({
      rotations: [bc],
    });
    expect(await followRotations(c.publicKey, c.publicKey, [ab, bc])).toEqual({ rotations: [] });
  });

  it('refuses a key change that no valid rotation explains', async () => {
    const [a, b, c] = await Promise.all([newKey(), newKey(), newKey()]);
    const ab = await rotate(a, b.publicKey, 5);
    const problem = async (all: KeyRotation[]) =>
      ((await followRotations(a.publicKey, c.publicKey, all)) as { problem: string }).problem;

    expect(await problem([])).toMatch(/key changed/);
    // "b -> c" signed by c itself, a link that skips b, one going back in size, and a dead end.
    const selfSigned = { ...(await rotate(c, c.publicKey, 9)), oldKey: b.publicKey };
    expect(await problem([ab, selfSigned])).toMatch(/without the previous key's signature/);
    expect(await problem([ab, await rotate(a, c.publicKey, 9)])).toMatch(
      /previous key's signature/,
    );
    expect(await problem([ab, await rotate(b, c.publicKey, 4)])).toMatch(/go back in time/);
    expect(await problem([ab])).toMatch(/don't lead to the ledger's current key/);
  });
});
