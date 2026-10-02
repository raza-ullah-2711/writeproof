import { toBase64Url } from '../crypto/base64url';

/** Must stay byte-identical to backend `LoginMessage`. */
export const LOGIN_DOMAIN = 'writeproof/login/v1';

const NONCE_LENGTH = 32;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/**
 * Builds the bytes the wallet signs to log in. The client constructs these itself from a
 * challenge id and nonce, so the server can never get the wallet to sign arbitrary data.
 */
export function loginMessage(challengeId: string, nonce: Uint8Array): Uint8Array<ArrayBuffer> {
  if (!UUID_PATTERN.test(challengeId)) {
    throw new Error('Invalid challenge id');
  }
  if (nonce.length !== NONCE_LENGTH) {
    throw new Error(`Nonce must be ${NONCE_LENGTH} bytes`);
  }
  return new TextEncoder().encode(`${LOGIN_DOMAIN}\n${challengeId}\n${toBase64Url(nonce)}`);
}
