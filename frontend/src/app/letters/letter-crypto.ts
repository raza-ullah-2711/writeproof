import { fromBase64Url, toBase64Url } from '../crypto/base64url';
import {
  LetterEnvelope,
  WrappedKey,
  handwritingHash,
  letterHash,
  letterHeader,
  letterHeaderV2,
} from './letter-format';

/**
 * Sealed delivery. The body is AES-256-GCM under a random content key; that key is wrapped
 * for each reader (recipient and sender) with an ephemeral X25519 exchange,
 * HKDF-SHA256(salt = ephemeral || reader key) and AES-GCM. The letter header is the associated
 * data everywhere, so ciphertext can't be moved into a different letter.
 */
const KEY_INFO = new TextEncoder().encode('writeproof/letter-key/v1');
const utf8 = (text: string) => new TextEncoder().encode(text);

export interface LetterPlaintext {
  body: string;
  /** The handwriting sample JSON that signed the letter (v2 letters). */
  handwriting?: string;
}

export interface SealInput {
  body: string;
  senderKey: string;
  recipientKey: string;
  senderEncryptionKey: string;
  recipientEncryptionKey: string;
  sentAt: string;
  /**
   * Exact handwriting JSON; sealed inside the letter and committed to by the v2 header. The app
   * always provides it (the server rejects letters without it); omitting it produces a v1 letter.
   */
  handwriting?: string;
}

export interface Sealed {
  envelope: LetterEnvelope;
  header: string;
  hash: Uint8Array<ArrayBuffer>;
}

/** Derives shared secrets with the reader's own (non-extractable) X25519 key. */
export type Ecdh = (ephemeralPublicKey: Uint8Array<ArrayBuffer>) => Promise<ArrayBuffer>;

export async function sealLetter(input: SealInput): Promise<Sealed> {
  const header =
    input.handwriting === undefined
      ? letterHeader(input.senderKey, input.recipientKey, input.sentAt)
      : letterHeaderV2(
          input.senderKey,
          input.recipientKey,
          input.sentAt,
          await handwritingHash(input.handwriting),
        );
  const aad = utf8(header);
  const contentKey = crypto.getRandomValues(new Uint8Array(32));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const bodyKey = await crypto.subtle.importKey('raw', contentKey, 'AES-GCM', false, ['encrypt']);
  const plaintext = utf8(
    JSON.stringify({ body: input.body, handwriting: input.handwriting } satisfies LetterPlaintext),
  );
  const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: aad },
    bodyKey,
    plaintext,
  );
  const envelope: LetterEnvelope = {
    version: 1,
    iv: toBase64Url(iv),
    ciphertext: toBase64Url(new Uint8Array(ciphertext)),
    recipientKey: await wrapFor(fromBase64Url(input.recipientEncryptionKey), contentKey, aad),
    senderKey: await wrapFor(fromBase64Url(input.senderEncryptionKey), contentKey, aad),
  };
  contentKey.fill(0);
  return { envelope, header, hash: await letterHash(header, envelope) };
}

/**
 * Decrypts a letter as one of its readers. Throws if anything was altered: a different
 * header, ciphertext or wrapped key all fail AES-GCM authentication.
 */
export async function openLetter(
  envelope: LetterEnvelope,
  header: string,
  as: 'recipient' | 'sender',
  myEncryptionKey: string,
  ecdh: Ecdh,
): Promise<LetterPlaintext> {
  const aad = utf8(header);
  const wrapped = as === 'recipient' ? envelope.recipientKey : envelope.senderKey;
  const ephemeral = fromBase64Url(wrapped.ephemeralPublicKey);
  const kek = await deriveKek(await ecdh(ephemeral), ephemeral, fromBase64Url(myEncryptionKey));
  const contentKey = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: fromBase64Url(wrapped.iv), additionalData: aad },
    kek,
    fromBase64Url(wrapped.wrappedKey),
  );
  const bodyKey = await crypto.subtle.importKey('raw', contentKey, 'AES-GCM', false, ['decrypt']);
  const plaintext = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: fromBase64Url(envelope.iv), additionalData: aad },
    bodyKey,
    fromBase64Url(envelope.ciphertext),
  );
  const parsed: unknown = JSON.parse(new TextDecoder().decode(plaintext));
  const letter = parsed as LetterPlaintext;
  if (
    typeof letter?.body !== 'string' ||
    (letter.handwriting !== undefined && typeof letter.handwriting !== 'string')
  ) {
    throw new Error('Malformed letter body');
  }
  return parsed as LetterPlaintext;
}

async function wrapFor(
  readerKey: Uint8Array<ArrayBuffer>,
  contentKey: Uint8Array<ArrayBuffer>,
  aad: Uint8Array<ArrayBuffer>,
): Promise<WrappedKey> {
  const ephemeral = (await crypto.subtle.generateKey({ name: 'X25519' }, false, [
    'deriveBits',
  ])) as CryptoKeyPair;
  const ephemeralPublic = new Uint8Array(await crypto.subtle.exportKey('raw', ephemeral.publicKey));
  const reader = await crypto.subtle.importKey('raw', readerKey, { name: 'X25519' }, false, []);
  const shared = await crypto.subtle.deriveBits(
    { name: 'X25519', public: reader },
    ephemeral.privateKey,
    256,
  );
  const kek = await deriveKek(shared, ephemeralPublic, readerKey);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const wrapped = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: aad },
    kek,
    contentKey,
  );
  return {
    ephemeralPublicKey: toBase64Url(ephemeralPublic),
    iv: toBase64Url(iv),
    wrappedKey: toBase64Url(new Uint8Array(wrapped)),
  };
}

async function deriveKek(
  shared: ArrayBuffer,
  ephemeralPublic: Uint8Array<ArrayBuffer>,
  readerKey: Uint8Array<ArrayBuffer>,
): Promise<CryptoKey> {
  const salt = new Uint8Array(64);
  salt.set(ephemeralPublic, 0);
  salt.set(readerKey, 32);
  const ikm = await crypto.subtle.importKey('raw', shared, 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt, info: KEY_INFO },
    ikm,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );
}
