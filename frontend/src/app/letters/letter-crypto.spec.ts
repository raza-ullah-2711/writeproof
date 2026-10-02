import { fromBase64Url, toBase64Url } from '../crypto/base64url';
import { Ecdh, openLetter, sealLetter } from './letter-crypto';

interface Party {
  identity: string;
  encryptionKey: string;
  ecdh: Ecdh;
}

async function party(): Promise<Party> {
  const keys = (await crypto.subtle.generateKey({ name: 'X25519' }, false, [
    'deriveBits',
  ])) as CryptoKeyPair;
  const raw = new Uint8Array(await crypto.subtle.exportKey('raw', keys.publicKey));
  return {
    identity: toBase64Url(crypto.getRandomValues(new Uint8Array(32))),
    encryptionKey: toBase64Url(raw),
    ecdh: async (ephemeral) => {
      const pub = await crypto.subtle.importKey('raw', ephemeral, { name: 'X25519' }, false, []);
      return crypto.subtle.deriveBits({ name: 'X25519', public: pub }, keys.privateKey, 256);
    },
  };
}

describe('letter crypto', () => {
  let alice: Party;
  let bob: Party;
  const sentAt = '2026-10-02T12:00:00.000Z';

  beforeEach(async () => {
    alice = await party();
    bob = await party();
  });

  const seal = (body: string) =>
    sealLetter({
      body,
      senderKey: alice.identity,
      recipientKey: bob.identity,
      senderEncryptionKey: alice.encryptionKey,
      recipientEncryptionKey: bob.encryptionKey,
      sentAt,
    });

  it('lets the recipient and the sender open the letter', async () => {
    const { envelope, header } = await seal('Dear Bob, ✉️ sealed by hand.');

    await expect(
      openLetter(envelope, header, 'recipient', bob.encryptionKey, bob.ecdh),
    ).resolves.toEqual({ body: 'Dear Bob, ✉️ sealed by hand.' });
    await expect(
      openLetter(envelope, header, 'sender', alice.encryptionKey, alice.ecdh),
    ).resolves.toEqual({ body: 'Dear Bob, ✉️ sealed by hand.' });
  });

  it('does not contain the plaintext anywhere in the envelope', async () => {
    const { envelope } = await seal('the secret word is quince');
    const json = JSON.stringify(envelope);

    expect(json).not.toContain('quince');
    expect(new TextDecoder().decode(fromBase64Url(envelope.ciphertext))).not.toContain('quince');
  });

  it('cannot be opened by anyone else', async () => {
    const { envelope, header } = await seal('for Bob only');
    const eve = await party();

    await expect(
      openLetter(envelope, header, 'recipient', eve.encryptionKey, eve.ecdh),
    ).rejects.toThrow();
  });

  it('fails if the ciphertext was altered', async () => {
    const { envelope, header } = await seal('original');
    const bytes = fromBase64Url(envelope.ciphertext);
    bytes[0] ^= 1;

    await expect(
      openLetter(
        { ...envelope, ciphertext: toBase64Url(bytes) },
        header,
        'recipient',
        bob.encryptionKey,
        bob.ecdh,
      ),
    ).rejects.toThrow();
  });

  it('fails if the ciphertext is presented under a different header', async () => {
    const { envelope, header } = await seal('original');
    const forgedHeader = header.replace(sentAt, '2026-10-03T12:00:00.000Z');

    await expect(
      openLetter(envelope, forgedHeader, 'recipient', bob.encryptionKey, bob.ecdh),
    ).rejects.toThrow();
  });

  it('uses fresh keys and nonces for every letter', async () => {
    const a = await seal('same');
    const b = await seal('same');

    expect(a.envelope.ciphertext).not.toBe(b.envelope.ciphertext);
    expect(a.envelope.recipientKey.ephemeralPublicKey).not.toBe(
      b.envelope.recipientKey.ephemeralPublicKey,
    );
    expect(toBase64Url(a.hash)).not.toBe(toBase64Url(b.hash));
  });
});
