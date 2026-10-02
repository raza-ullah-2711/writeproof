import { toBase64Url } from '../crypto/base64url';

/**
 * Canonical bytes shared with the backend (`LetterHashing`, `EncryptionKeyBinding`,
 * `LedgerHashing`); pinned by the same test vectors on both sides.
 */
export interface WrappedKey {
  ephemeralPublicKey: string;
  iv: string;
  wrappedKey: string;
}

export interface LetterEnvelope {
  version: 1;
  iv: string;
  ciphertext: string;
  recipientKey: WrappedKey;
  senderKey: WrappedKey;
}

export const LETTER_DOMAIN = 'writeproof/letter/v1';
export const LETTER_SIGNATURE_DOMAIN = 'writeproof/letter-signature/v1';
export const ENCRYPTION_KEY_DOMAIN = 'writeproof/encryption-key/v1';
export const LEDGER_DOMAIN = 'writeproof/ledger/v1';

const utf8 = (text: string) => new TextEncoder().encode(text);

/** Binds sender, recipient and time; also the AES-GCM associated data. */
export function letterHeader(senderKey: string, recipientKey: string, sentAt: string): string {
  return `${LETTER_DOMAIN}\n${senderKey}\n${recipientKey}\n${sentAt}`;
}

/** SHA-256 over the header and every envelope field: what the ledger records. */
export async function letterHash(
  header: string,
  e: LetterEnvelope,
): Promise<Uint8Array<ArrayBuffer>> {
  const preimage = [
    header,
    e.iv,
    e.ciphertext,
    e.recipientKey.ephemeralPublicKey,
    e.recipientKey.iv,
    e.recipientKey.wrappedKey,
    e.senderKey.ephemeralPublicKey,
    e.senderKey.iv,
    e.senderKey.wrappedKey,
  ].join('\n');
  return sha256(utf8(preimage));
}

/** What the sender's identity key signs: domain-separated from login messages. */
export function letterSignedMessage(hash: Uint8Array): Uint8Array<ArrayBuffer> {
  return utf8(`${LETTER_SIGNATURE_DOMAIN}\n${toBase64Url(hash)}`);
}

/** What an identity key signs to vouch for its X25519 encryption key. */
export function encryptionKeyBinding(
  identityKey: string,
  encryptionKey: string,
): Uint8Array<ArrayBuffer> {
  return utf8(`${ENCRYPTION_KEY_DOMAIN}\n${identityKey}\n${encryptionKey}`);
}

export async function sha256(data: Uint8Array<ArrayBuffer>): Promise<Uint8Array<ArrayBuffer>> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', data));
}
