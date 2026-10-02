import { toBase64Url } from '../crypto/base64url';
import { sha256 } from '../letters/letter-format';

/** Mirrors backend `OpenLetterHashing`; pinned by a shared vector. */
export const OPEN_LETTER_DOMAIN = 'writeproof/open-letter/v1';

const utf8 = (text: string) => new TextEncoder().encode(text);

export async function bodyHash(body: string): Promise<string> {
  return toBase64Url(await sha256(utf8(body)));
}

/** What goes on the ledger and what the author's wallet signs (as `letterSignedMessage`). */
export async function openLetterHash(
  author: string,
  sentAt: string,
  handwritingHash: string,
  body: string,
): Promise<Uint8Array<ArrayBuffer>> {
  const preimage = `${OPEN_LETTER_DOMAIN}\n${author}\n${sentAt}\n${handwritingHash}\n${await bodyHash(body)}`;
  return sha256(utf8(preimage));
}
