import { fromBase64Url, toBase64Url } from '../crypto/base64url';
import {
  LetterEnvelope,
  encryptionKeyBinding,
  handwritingHash,
  letterHash,
  letterHeader,
  letterHeaderV2,
  letterHeaderV3,
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

  it('builds the hand-signed (v2) header and hash', async () => {
    const hw = await handwritingHash('{"format":"writeproof.handwriting"}');
    const header = letterHeaderV2(bytes(1, 32), bytes(101, 32), '2026-10-02T12:00:00.000Z', hw);

    expect(hw).toBe('ENPXVYNKkitwzrhusxhzz4zgCM6PlNr31tF_pUxw9QQ');
    expect(header).toBe(
      'writeproof/letter/v2\n' +
        'AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA\n' +
        'ZWZnaGlqa2xtbm9wcXJzdHV2d3h5ent8fX5_gIGCg4Q\n' +
        '2026-10-02T12:00:00.000Z\n' +
        'ENPXVYNKkitwzrhusxhzz4zgCM6PlNr31tF_pUxw9QQ',
    );
    expect(toBase64Url(await letterHash(header, envelope))).toBe(
      '0POqYGKL8NfQmv7K-jvj8VrFCxClE7yi8Tu3WRxPMKE',
    );
  });

  it('builds the reply (v3) header and hash', async () => {
    const hw = await handwritingHash('{"format":"writeproof.handwriting"}');
    const header = letterHeaderV3(
      bytes(101, 32),
      bytes(1, 32),
      '2026-10-02T12:05:00.000Z',
      hw,
      '0POqYGKL8NfQmv7K-jvj8VrFCxClE7yi8Tu3WRxPMKE',
    );

    expect(header).toBe(
      'writeproof/letter/v3\n' +
        'ZWZnaGlqa2xtbm9wcXJzdHV2d3h5ent8fX5_gIGCg4Q\n' +
        'AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA\n' +
        '2026-10-02T12:05:00.000Z\n' +
        'ENPXVYNKkitwzrhusxhzz4zgCM6PlNr31tF_pUxw9QQ\n' +
        '0POqYGKL8NfQmv7K-jvj8VrFCxClE7yi8Tu3WRxPMKE',
    );
    expect(toBase64Url(await letterHash(header, envelope))).toBe(
      'o9Kh3vyRofTJuiyDzC6mG2PPLAiIGq5wHo0iv9KT4fQ',
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
