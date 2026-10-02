import { fromBase64Url, toBase64Url } from '../crypto/base64url';

/** A petname: what *you* call an address. Private to you; never sent to anyone in the clear. */
export interface Contact {
  address: string;
  petname: string;
  addedAt: string;
}

/** The decrypted contact book. */
export interface ContactBook {
  format: 'writeproof.contacts';
  version: 1;
  contacts: Contact[];
}

export const MAX_PETNAME_LENGTH = 40;
const DOMAIN = 'writeproof/contacts/v1';
const utf8 = (text: string) => new TextEncoder().encode(text);

export function emptyBook(): ContactBook {
  return { format: 'writeproof.contacts', version: 1, contacts: [] };
}

/** Trims, collapses whitespace and normalises; throws if the result isn't a usable petname. */
export function normalizePetname(input: string): string {
  const petname = input.normalize('NFC').trim().replace(/\s+/g, ' ');
  if (petname.length === 0 || petname.length > MAX_PETNAME_LENGTH) {
    throw new Error(`A name needs 1 to ${MAX_PETNAME_LENGTH} characters`);
  }
  if (/\p{Cc}|\p{Cf}/u.test(petname)) {
    throw new Error('A name cannot contain control or invisible characters');
  }
  return petname;
}

/** True if `address` is a well-formed account address (a raw 32-byte Ed25519 key, base64url). */
export function isAddress(address: string): boolean {
  try {
    return /^[A-Za-z0-9_-]{43}$/.test(address) && fromBase64Url(address).length === 32;
  } catch {
    return false;
  }
}

const sameName = (a: string, b: string) => a.toLocaleLowerCase() === b.toLocaleLowerCase();

/**
 * Adds a contact. Names are unique (ignoring case), so a name always means one person: a
 * newcomer can't take a name you already use for someone else.
 */
export function addContact(
  book: ContactBook,
  address: string,
  petname: string,
  now: Date,
): ContactBook {
  if (!isAddress(address)) {
    throw new Error('That is not a Writeproof address');
  }
  const name = normalizePetname(petname);
  const existing = book.contacts.find((c) => c.address === address);
  if (existing) {
    throw new Error(`Already in your contacts as “${existing.petname}”`);
  }
  if (book.contacts.some((c) => sameName(c.petname, name))) {
    throw new Error(`You already have a contact called “${name}”`);
  }
  return {
    ...book,
    contacts: [...book.contacts, { address, petname: name, addedAt: now.toISOString() }],
  };
}

export function renameContact(book: ContactBook, address: string, petname: string): ContactBook {
  const name = normalizePetname(petname);
  if (!book.contacts.some((c) => c.address === address)) {
    throw new Error('No such contact');
  }
  if (book.contacts.some((c) => c.address !== address && sameName(c.petname, name))) {
    throw new Error(`You already have a contact called “${name}”`);
  }
  return {
    ...book,
    contacts: book.contacts.map((c) => (c.address === address ? { ...c, petname: name } : c)),
  };
}

export function removeContact(book: ContactBook, address: string): ContactBook {
  return { ...book, contacts: book.contacts.filter((c) => c.address !== address) };
}

/**
 * The contact book key. The wallet's X25519 key agrees with itself, so only the wallet holder can
 * derive it, and a wallet restored from backup derives the same key on any device.
 */
export async function contactBookKey(
  selfAgreement: ArrayBuffer,
  publicKey: string,
): Promise<CryptoKey> {
  const ikm = await crypto.subtle.importKey('raw', selfAgreement, 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt: utf8(DOMAIN), info: utf8(`key\n${publicKey}`) },
    ikm,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );
}

/**
 * The associated data binds the ciphertext to the account and to the version it is stored as,
 * so the server can't hand one account's book to another or relabel an old book as current.
 */
function aad(publicKey: string, version: number): Uint8Array<ArrayBuffer> {
  return utf8(`${DOMAIN}\n${publicKey}\n${version}`);
}

/** Returns base64url(iv || ciphertext) for storing as `version`. */
export async function encryptContactBook(
  key: CryptoKey,
  book: ContactBook,
  publicKey: string,
  version: number,
): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = new Uint8Array(
    await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv, additionalData: aad(publicKey, version) },
      key,
      utf8(JSON.stringify(book)),
    ),
  );
  const out = new Uint8Array(iv.length + ciphertext.length);
  out.set(iv);
  out.set(ciphertext, iv.length);
  return toBase64Url(out);
}

/** @throws Error if the book was altered, is someone else's, or is not the stored version */
export async function decryptContactBook(
  key: CryptoKey,
  blob: string,
  publicKey: string,
  version: number,
): Promise<ContactBook> {
  let plaintext: ArrayBuffer;
  try {
    const bytes = fromBase64Url(blob);
    plaintext = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: bytes.slice(0, 12), additionalData: aad(publicKey, version) },
      key,
      bytes.slice(12),
    );
  } catch {
    throw new Error('Your contact book could not be decrypted: it was altered or is not yours');
  }
  const book = JSON.parse(new TextDecoder().decode(plaintext)) as ContactBook;
  if (
    book.format !== 'writeproof.contacts' ||
    book.version !== 1 ||
    !Array.isArray(book.contacts)
  ) {
    throw new Error('Unsupported contact book format');
  }
  return book;
}
