import { fromBase64Url, toBase64Url } from '../crypto/base64url';
import {
  LetterEnvelope,
  encryptionKeyBinding,
  letterHash,
  letterHeader,
  letterSignedMessage,
} from './letter-format';

const bytes = (from: number, n: number) =>
  toBase64Url(Uint8Array.from({ length: n }, (_, i) => (from + i) & 0xff));

/** Same vectors as backend FormatVectorsTest. Change both or neither. */
describe('letter format (shared vectors with the backend)', () => {
  const envelope: LetterEnvelope = {
    version: 1,
    iv: bytes(1, 12),
    ciphertext: bytes(50, 40),
    recipientKey: {
      ephemeralPublicKey: bytes(100, 32),
      iv: bytes(140, 12),
      wrappedKey: bytes(160, 48),
    },
    senderKey: {
      ephemeralPublicKey: bytes(200, 32),
      iv: bytes(240, 12),
      wrappedKey: bytes(10, 48),
    },
  };

  it('builds the header', () => {
    expect(letterHeader(bytes(1, 32), bytes(101, 32), '2026-10-02T12:00:00.000Z')).toBe(
      'writeproof/letter/v1\n' +
        'AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA\n' +
        'ZWZnaGlqa2xtbm9wcXJzdHV2d3h5ent8fX5_gIGCg4Q\n' +
        '2026-10-02T12:00:00.000Z',
    );
  });

  it('hashes the letter and builds the signed message', async () => {
    const header = letterHeader(bytes(1, 32), bytes(101, 32), '2026-10-02T12:00:00.000Z');
    const hash = await letterHash(header, envelope);

    expect(toBase64Url(hash)).toBe('9EcA6cSC4xiiDwmYh6svDBId42vzq_j1zMPjTcZZmTs');
    expect(new TextDecoder().decode(letterSignedMessage(hash))).toBe(
      'writeproof/letter-signature/v1\n9EcA6cSC4xiiDwmYh6svDBId42vzq_j1zMPjTcZZmTs',
    );
  });

  it('builds the encryption key binding', () => {
    expect(new TextDecoder().decode(encryptionKeyBinding(bytes(1, 32), bytes(101, 32)))).toBe(
      'writeproof/encryption-key/v1\n' +
        'AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA\n' +
        'ZWZnaGlqa2xtbm9wcXJzdHV2d3h5ent8fX5_gIGCg4Q',
    );
  });

  it('changes the hash if any envelope field changes', async () => {
    const header = letterHeader(bytes(1, 32), bytes(101, 32), '2026-10-02T12:00:00.000Z');
    const original = toBase64Url(await letterHash(header, envelope));
    const altered = { ...envelope, senderKey: { ...envelope.senderKey, iv: bytes(0, 12) } };

    expect(toBase64Url(await letterHash(header, altered))).not.toBe(original);
    expect(fromBase64Url(original)).toHaveLength(32);
  });
});
