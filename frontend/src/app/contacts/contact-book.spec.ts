import { fromBase64Url, toBase64Url } from '../crypto/base64url';
import {
  ContactBook,
  addContact,
  contactBookKey,
  decryptContactBook,
  emptyBook,
  encryptContactBook,
  isAddress,
  normalizePetname,
  removeContact,
  renameContact,
} from './contact-book';

const address = (n: number) => toBase64Url(new Uint8Array(32).fill(n));
const NOW = new Date('2026-10-02T12:00:00.000Z');

async function selfAgreement(): Promise<{
  secret: ArrayBuffer;
  again: () => Promise<ArrayBuffer>;
}> {
  const pair = (await crypto.subtle.generateKey({ name: 'X25519' }, true, [
    'deriveBits',
  ])) as CryptoKeyPair;
  const derive = () =>
    crypto.subtle.deriveBits({ name: 'X25519', public: pair.publicKey }, pair.privateKey, 256);
  return { secret: await derive(), again: derive };
}

describe('contact book', () => {
  it('normalises petnames and rejects unusable ones', () => {
    expect(normalizePetname('  Aunt   Rosa ')).toBe('Aunt Rosa');
    expect(normalizePetname('José')).toBe('José');
    expect(() => normalizePetname('   ')).toThrow(/1 to 40/);
    expect(() => normalizePetname('x'.repeat(41))).toThrow(/1 to 40/);
    expect(() => normalizePetname('Mum​')).toThrow(/invisible/);
    expect(() => normalizePetname('a\u0007b')).toThrow(/control/);
  });

  it('recognises addresses', () => {
    expect(isAddress(address(1))).toBe(true);
    expect(isAddress(address(1).slice(1))).toBe(false);
    expect(isAddress(address(1) + 'A')).toBe(false);
    expect(isAddress('not an address at all, but 43 chars long!!')).toBe(false);
  });

  it('keeps names and addresses unique', () => {
    let book = addContact(emptyBook(), address(1), 'Mum', NOW);
    book = addContact(book, address(2), 'Rosa', NOW);

    expect(book.contacts).toEqual([
      { address: address(1), petname: 'Mum', addedAt: NOW.toISOString() },
      { address: address(2), petname: 'Rosa', addedAt: NOW.toISOString() },
    ]);
    expect(() => addContact(book, address(1), 'Mother', NOW)).toThrow(/Already .* as “Mum”/);
    expect(() => addContact(book, address(3), 'mum', NOW)).toThrow(/already have a contact/);
    expect(() => addContact(book, 'nope', 'X', NOW)).toThrow(/not a Writeproof address/);
    expect(() => renameContact(book, address(2), 'MUM')).toThrow(/already have a contact/);
    expect(renameContact(book, address(1), 'mum').contacts[0].petname).toBe('mum');
    expect(() => renameContact(book, address(9), 'X')).toThrow(/No such contact/);
    expect(removeContact(book, address(1)).contacts.map((c) => c.petname)).toEqual(['Rosa']);
  });

  it('round-trips through encryption, and the same wallet derives the same key again', async () => {
    const { secret, again } = await selfAgreement();
    const book: ContactBook = addContact(emptyBook(), address(1), 'Mum', NOW);
    const blob = await encryptContactBook(
      await contactBookKey(secret, address(7)),
      book,
      address(7),
      3,
    );

    const otherDevice = await contactBookKey(await again(), address(7));
    await expect(decryptContactBook(otherDevice, blob, address(7), 3)).resolves.toEqual(book);
  });

  it('refuses a book relabelled with another version or account, altered, or under another key', async () => {
    const { secret } = await selfAgreement();
    const key = await contactBookKey(secret, address(7));
    const blob = await encryptContactBook(key, emptyBook(), address(7), 3);
    const bytes = fromBase64Url(blob);
    bytes[20] ^= 1;
    const stranger = await contactBookKey((await selfAgreement()).secret, address(7));

    for (const attempt of [
      decryptContactBook(key, blob, address(7), 4),
      decryptContactBook(key, blob, address(8), 3),
      decryptContactBook(key, toBase64Url(bytes), address(7), 3),
      decryptContactBook(stranger, blob, address(7), 3),
    ]) {
      await expect(attempt).rejects.toThrow(/could not be decrypted/);
    }
  });
});
