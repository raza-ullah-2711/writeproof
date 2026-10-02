import { toBase64Url } from '../crypto/base64url';
import { handwritingHash } from '../letters/letter-format';
import { bodyHash, openLetterHash } from './open-letter-format';

const author = toBase64Url(Uint8Array.from({ length: 32 }, (_, i) => i + 1));

describe('open letter format (shared vector with backend OpenLettersApiTests)', () => {
  it('hashes the body and the letter', async () => {
    const body = 'Dear world,\nhello. ✍';
    const hw = await handwritingHash('{"format":"writeproof.handwriting"}');

    expect(await bodyHash(body)).toBe('h-UOIzwEcPgmIvLyKrxbUJRS7I4UHy_BCsflqL9ePYY');
    expect(toBase64Url(await openLetterHash(author, '2026-10-02T12:00:00.000Z', hw, body))).toBe(
      'itdvQZnNNB8MKApaQ228VyrJ8vt9G37LpRutcNboHq4',
    );
  });

  it('changes if any part changes', async () => {
    const hw = await handwritingHash('{}');
    const base = toBase64Url(await openLetterHash(author, '2026-10-02T12:00:00.000Z', hw, 'a'));
    expect(
      toBase64Url(await openLetterHash(author, '2026-10-02T12:00:00.000Z', hw, 'a ')),
    ).not.toBe(base);
    expect(toBase64Url(await openLetterHash(author, '2026-10-02T12:00:00.001Z', hw, 'a'))).not.toBe(
      base,
    );
  });
});
