import { fromBase64Url } from './base64url';

/** Verifies an Ed25519 signature; malformed keys or signatures verify as false. */
export async function verifyEd25519(
  publicKey: string,
  message: Uint8Array<ArrayBuffer>,
  signature: string,
): Promise<boolean> {
  try {
    const key = await crypto.subtle.importKey(
      'raw',
      fromBase64Url(publicKey),
      { name: 'Ed25519' },
      false,
      ['verify'],
    );
    return await crypto.subtle.verify('Ed25519', key, fromBase64Url(signature), message);
  } catch {
    return false;
  }
}
