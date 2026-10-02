import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { IDBFactory } from 'fake-indexeddb';
import { nextRequest } from '../../testing/http';
import { fromBase64Url, toBase64Url } from '../crypto/base64url';
import { WalletService } from '../wallet/wallet.service';
import {
  ContactBook,
  addContact,
  contactBookKey,
  decryptContactBook,
  emptyBook,
  encryptContactBook,
} from './contact-book';
import { ContactsService } from './contacts.service';

const address = (n: number) => toBase64Url(new Uint8Array(32).fill(n));

describe('ContactsService', () => {
  let contacts: ContactsService;
  let wallet: WalletService;
  let http: HttpTestingController;

  beforeEach(async () => {
    globalThis.indexedDB = new IDBFactory();
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    contacts = TestBed.inject(ContactsService);
    wallet = TestBed.inject(WalletService);
    http = TestBed.inject(HttpTestingController);
    await wallet.create();
  });

  afterEach(() => http.verify());

  /** What the server sees is only this; the test can read it because it holds the wallet. */
  async function key() {
    const own = fromBase64Url(wallet.encryptionPublicKey()!);
    return contactBookKey(await wallet.ecdh(own), wallet.publicKey()!);
  }

  async function serverBook(version: number, book: ContactBook) {
    return {
      version,
      ciphertext: await encryptContactBook(await key(), book, wallet.publicKey()!, version),
    };
  }

  async function loadEmpty() {
    const loading = contacts.ensureLoaded();
    (await nextRequest(http, '/api/me/contacts')).flush({ version: 0, ciphertext: null });
    await loading;
  }

  async function confirmAccount(a: string) {
    (await nextRequest(http, `/api/accounts/by-key/${a}`)).flush({ accountId: 'x', publicKey: a });
  }

  it('starts empty and saves an encrypted book the server cannot read', async () => {
    await loadEmpty();
    expect(contacts.contacts()).toEqual([]);

    const adding = contacts.add(address(2), ' Bob  from choir ');
    await confirmAccount(address(2));
    const put = await nextRequest(http, '/api/me/contacts');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body.baseVersion).toBe(0);
    expect(put.request.body.ciphertext).not.toContain('Bob');
    put.flush({ version: 1 });
    await adding;

    const stored = await decryptContactBook(
      await key(),
      put.request.body.ciphertext,
      wallet.publicKey()!,
      1,
    );
    expect(stored.contacts).toEqual([
      expect.objectContaining({ address: address(2), petname: 'Bob from choir' }),
    ]);
    expect(contacts.petname(address(2))).toBe('Bob from choir');
    expect(contacts.petname(address(3))).toBeNull();
  });

  it('loads and decrypts an existing book, sorted by name', async () => {
    let book = addContact(emptyBook(), address(2), 'Zoe', new Date());
    book = addContact(book, address(3), 'Ana', new Date());
    const loading = contacts.ensureLoaded();
    (await nextRequest(http, '/api/me/contacts')).flush(await serverBook(4, book));
    await loading;

    expect(contacts.contacts()!.map((c) => c.petname)).toEqual(['Ana', 'Zoe']);
  });

  it('re-applies an edit on top of a book another device saved first', async () => {
    await loadEmpty();
    const adding = contacts.add(address(2), 'Bob');
    await confirmAccount(address(2));
    (await nextRequest(http, '/api/me/contacts')).flush('conflict', {
      status: 409,
      statusText: 'Conflict',
    });
    const fromPhone = addContact(emptyBook(), address(3), 'Carol', new Date());
    (await nextRequest(http, '/api/me/contacts')).flush(await serverBook(1, fromPhone));
    const retry = await nextRequest(http, '/api/me/contacts');
    expect(retry.request.body.baseVersion).toBe(1);
    retry.flush({ version: 2 });
    await adding;

    expect(contacts.contacts()!.map((c) => c.petname)).toEqual(['Bob', 'Carol']);
  });

  it('refuses to add yourself, non-addresses, unknown accounts or a taken name', async () => {
    await loadEmpty();
    await expect(contacts.add(wallet.publicKey()!, 'Me')).rejects.toThrow(/your own address/);
    await expect(contacts.add('hello', 'X')).rejects.toThrow(/not a Writeproof address/);

    const unknown = contacts.add(address(9), 'Ghost');
    (await nextRequest(http, `/api/accounts/by-key/${address(9)}`)).flush('', {
      status: 404,
      statusText: 'Not Found',
    });
    await expect(unknown).rejects.toThrow(/No account has that address/);

    const swapped = contacts.add(address(4), 'Dan');
    (await nextRequest(http, `/api/accounts/by-key/${address(4)}`)).flush({
      accountId: 'x',
      publicKey: address(5),
    });
    await expect(swapped).rejects.toThrow(/different account/);
  });

  it('renames and removes', async () => {
    const loading = contacts.ensureLoaded();
    const book = addContact(emptyBook(), address(2), 'Bob', new Date());
    (await nextRequest(http, '/api/me/contacts')).flush(await serverBook(1, book));
    await loading;

    const renaming = contacts.rename(address(2), 'Robert');
    (await nextRequest(http, '/api/me/contacts')).flush({ version: 2 });
    await renaming;
    expect(contacts.petname(address(2))).toBe('Robert');

    const removing = contacts.remove(address(2));
    const put = await nextRequest(http, '/api/me/contacts');
    expect(put.request.body.baseVersion).toBe(2);
    put.flush({ version: 3 });
    await removing;
    expect(contacts.contacts()).toEqual([]);
  });

  it('detects the server rolling the book back to an older version', async () => {
    const book = addContact(emptyBook(), address(2), 'Bob', new Date());
    const first = contacts.ensureLoaded();
    (await nextRequest(http, '/api/me/contacts')).flush(await serverBook(5, book));
    await first;

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: WalletService, useValue: wallet },
      ],
    });
    const fresh = TestBed.inject(ContactsService);
    const http2 = TestBed.inject(HttpTestingController);
    const reload = fresh.ensureLoaded();
    (await nextRequest(http2, '/api/me/contacts')).flush(await serverBook(3, emptyBook()));

    await expect(reload).rejects.toThrow(/older contact book/);
    http = http2;
  });

  it('treats an emptied book as a rollback once a version was seen', async () => {
    const first = contacts.ensureLoaded();
    (await nextRequest(http, '/api/me/contacts')).flush(await serverBook(2, emptyBook()));
    await first;
    const reload = contacts['load']();
    (await nextRequest(http, '/api/me/contacts')).flush({ version: 0, ciphertext: null });

    await expect(reload).rejects.toThrow(/older contact book \(version 0/);
  });

  it('refuses a book the server relabelled with a newer version', async () => {
    const stale = await serverBook(2, emptyBook());
    const loading = contacts.ensureLoaded();
    (await nextRequest(http, '/api/me/contacts')).flush({ ...stale, version: 3 });

    await expect(loading).rejects.toThrow(/could not be decrypted/);
  });
});
