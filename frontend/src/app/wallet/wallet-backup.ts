import { fromBase64Url, toBase64Url } from '../crypto/base64url';

/** Private keys leave IndexedDB only inside this, and only to be encrypted for a backup. */
export interface WalletKeys {
  publicKey: string;
  encryptionPublicKey: string;
  identityPkcs8: string;
  encryptionPkcs8: string;
}

/** What the server stores. Only `publicKey` (the account address) is readable without the code. */
export interface WalletBackupBlob {
  format: 'writeproof.wallet-backup';
  version: 1;
  publicKey: string;
  salt: string;
  iv: string;
  ciphertext: string;
  createdAt: string;
}

const DOMAIN = 'writeproof/wallet-backup/v1';
const utf8 = (text: string) => new TextEncoder().encode(text);

/**
 * Where the backup is filed. Derived from the code with a fixed salt, so a new device can find
 * the backup from the code alone. The code has 128 bits of entropy, so this can't be guessed
 * or inverted, and a fast KDF is enough.
 */
export async function backupLookupId(code: Uint8Array<ArrayBuffer>): Promise<string> {
  return toBase64Url(await hkdf(code, utf8(DOMAIN), 'lookup', 32));
}

export async function encryptBackup(
  code: Uint8Array<ArrayBuffer>,
  keys: WalletKeys,
): Promise<WalletBackupBlob> {
  const salt = crypto.getRandomValues(new Uint8Array(32));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await backupKey(code, salt);
  const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: aad(keys.publicKey) },
    key,
    utf8(JSON.stringify(keys)),
  );
  return {
    format: 'writeproof.wallet-backup',
    version: 1,
    publicKey: keys.publicKey,
    salt: toBase64Url(salt),
    iv: toBase64Url(iv),
    ciphertext: toBase64Url(new Uint8Array(ciphertext)),
    createdAt: new Date().toISOString(),
  };
}

/** @throws Error if the code is wrong or the backup was altered */
export async function decryptBackup(
  code: Uint8Array<ArrayBuffer>,
  blob: WalletBackupBlob,
): Promise<WalletKeys> {
  if (blob.format !== 'writeproof.wallet-backup' || blob.version !== 1) {
    throw new Error('Not a Writeproof wallet backup');
  }
  const key = await backupKey(code, fromBase64Url(blob.salt));
  let plaintext: ArrayBuffer;
  try {
    plaintext = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: fromBase64Url(blob.iv), additionalData: aad(blob.publicKey) },
      key,
      fromBase64Url(blob.ciphertext),
    );
  } catch {
    throw new Error('This backup could not be decrypted with that recovery code');
  }
  const keys = JSON.parse(new TextDecoder().decode(plaintext)) as WalletKeys;
  if (keys.publicKey !== blob.publicKey) {
    throw new Error('This backup is inconsistent');
  }
  return keys;
}

const aad = (publicKey: string) => utf8(`${DOMAIN}\n${publicKey}`);

async function backupKey(
  code: Uint8Array<ArrayBuffer>,
  salt: Uint8Array<ArrayBuffer>,
): Promise<CryptoKey> {
  const raw = await hkdf(code, salt, 'key', 32);
  return crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']);
}

async function hkdf(
  ikm: Uint8Array<ArrayBuffer>,
  salt: Uint8Array<ArrayBuffer>,
  info: string,
  length: number,
): Promise<Uint8Array<ArrayBuffer>> {
  const base = await crypto.subtle.importKey('raw', ikm, 'HKDF', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits(
    { name: 'HKDF', hash: 'SHA-256', salt, info: utf8(`${DOMAIN}/${info}`) },
    base,
    length * 8,
  );
  return new Uint8Array(bits);
}
