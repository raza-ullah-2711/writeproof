import { toBase64Url } from '../crypto/base64url';
import { entryHash } from './ledger-verify';

const bytes = (from: number, n: number) =>
  toBase64Url(Uint8Array.from({ length: n }, (_, i) => (from + i) & 0xff));

describe('ledger entry hash', () => {
  it('matches the backend entry hash (shared vector with FormatVectorsTest)', async () => {
    expect(await entryHash(3, bytes(0, 32), bytes(32, 32), 1_790_000_000_123)).toBe(
      'YkX8efNwvw09CuvGvt9Cs6Lllmff-CZT-urM_XkFWlY',
    );
  });
});
