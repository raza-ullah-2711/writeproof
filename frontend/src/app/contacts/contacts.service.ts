import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Account } from '../auth/auth.service';
import { fromBase64Url } from '../crypto/base64url';
import { WalletService } from '../wallet/wallet.service';
import {
  Contact,
  ContactBook,
  addContact,
  contactBookKey,
  decryptContactBook,
  emptyBook,
  encryptContactBook,
  isAddress,
  removeContact,
  renameContact,
} from './contact-book';

interface StoredBook {
  /** 0 (and no ciphertext) until the first save. */
  version: number;
  ciphertext: string | null;
}

const SEEN_VERSION = 'writeproof.contacts-version.';

/**
 * Your contact book: petnames for addresses. Encrypted in the browser with a key only your wallet
 * can derive; the server stores ciphertext and a version number, nothing else.
 */
@Injectable({ providedIn: 'root' })
export class ContactsService {
  private readonly http = inject(HttpClient);
  private readonly wallet = inject(WalletService);

  private readonly book = signal<ContactBook | null>(null);
  private version = 0;
  private owner: string | null = null;
  private loading: Promise<void> | null = null;

  /** Sorted by name; null until loaded. */
  readonly contacts = computed(() =>
    this.book()
      ? [...this.book()!.contacts].sort((a, b) => a.petname.localeCompare(b.petname))
      : null,
  );
  private readonly byAddress = computed(
    () => new Map((this.book()?.contacts ?? []).map((c) => [c.address, c] as const)),
  );

  /** What you call this address, if it is one of your contacts. */
  petname(address: string): string | null {
    return this.byAddress().get(address)?.petname ?? null;
  }

  /** Loads the book for the current wallet, once (call again after switching wallets). */
  ensureLoaded(): Promise<void> {
    if (this.owner !== this.wallet.publicKey() || !this.loading) {
      this.loading = this.load().catch((e) => {
        this.loading = null;
        throw e;
      });
    }
    return this.loading;
  }

  async add(address: string, petname: string): Promise<Contact> {
    address = address.trim();
    if (!isAddress(address)) {
      throw new Error('That is not a Writeproof address');
    }
    if (address === this.wallet.publicKey()) {
      throw new Error('That is your own address');
    }
    let account: Account;
    try {
      account = await firstValueFrom(
        this.http.get<Account>(`/api/accounts/by-key/${encodeURIComponent(address)}`),
      );
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 404) {
        throw new Error('No account has that address');
      }
      throw e;
    }
    if (account.publicKey !== address) {
      throw new Error('The server returned a different account than the address asked for');
    }
    await this.change((book) => addContact(book, address, petname, new Date()));
    return this.byAddress().get(address)!;
  }

  rename(address: string, petname: string): Promise<void> {
    return this.change((book) => renameContact(book, address, petname));
  }

  remove(address: string): Promise<void> {
    return this.change((book) => removeContact(book, address));
  }

  private async load(): Promise<void> {
    const publicKey = this.requirePublicKey();
    this.owner = publicKey;
    this.book.set(null);
    const stored = await firstValueFrom(this.http.get<StoredBook>('/api/me/contacts'));
    const seen = readSeenVersion(publicKey);
    if (stored.version < seen) {
      throw new Error(
        `The server returned an older contact book (version ${stored.version}, this browser has seen ${seen})`,
      );
    }
    this.version = stored.version;
    this.book.set(
      stored.version > 0 && stored.ciphertext
        ? await decryptContactBook(await this.key(), stored.ciphertext, publicKey, stored.version)
        : emptyBook(),
    );
    writeSeenVersion(publicKey, this.version);
  }

  /** Applies `edit` and saves; if another device saved first, reloads and applies it again. */
  private async change(edit: (book: ContactBook) => ContactBook): Promise<void> {
    await this.ensureLoaded();
    for (let attempt = 0; ; attempt++) {
      const next = edit(this.book()!);
      const publicKey = this.requirePublicKey();
      const version = this.version + 1;
      try {
        await firstValueFrom(
          this.http.put<StoredBook>('/api/me/contacts', {
            baseVersion: this.version,
            ciphertext: await encryptContactBook(await this.key(), next, publicKey, version),
          }),
        );
      } catch (e) {
        if (e instanceof HttpErrorResponse && e.status === 409 && attempt === 0) {
          await this.load();
          continue;
        }
        throw e;
      }
      this.version = version;
      this.book.set(next);
      writeSeenVersion(publicKey, version);
      return;
    }
  }

  private async key(): Promise<CryptoKey> {
    const publicKey = this.requirePublicKey();
    const own = this.wallet.encryptionPublicKey();
    if (!own) {
      throw new Error('No wallet is loaded');
    }
    return contactBookKey(await this.wallet.ecdh(fromBase64Url(own)), publicKey);
  }

  private requirePublicKey(): string {
    const publicKey = this.wallet.publicKey();
    if (!publicKey) {
      throw new Error('No wallet is loaded');
    }
    return publicKey;
  }
}

/** The highest version this browser has seen, so the server can't quietly roll the book back. */
function readSeenVersion(publicKey: string): number {
  try {
    return Number(localStorage.getItem(SEEN_VERSION + publicKey)) || 0;
  } catch {
    return 0;
  }
}

function writeSeenVersion(publicKey: string, version: number): void {
  try {
    if (version > readSeenVersion(publicKey)) {
      localStorage.setItem(SEEN_VERSION + publicKey, String(version));
    }
  } catch {
    // Storage unavailable: rollback detection is best-effort.
  }
}
