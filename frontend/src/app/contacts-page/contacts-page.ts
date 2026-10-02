import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { Contact, MAX_PETNAME_LENGTH } from '../contacts/contact-book';
import { ContactsService } from '../contacts/contacts.service';
import { QrCode } from '../contacts/qr-code';
import { WalletService } from '../wallet/wallet.service';

@Component({
  selector: 'app-contacts-page',
  imports: [FormsModule, RouterLink, QrCode],
  templateUrl: './contacts-page.html',
  styleUrl: './contacts-page.scss',
})
export class ContactsPage implements OnInit {
  protected readonly auth = inject(AuthService);
  protected readonly wallet = inject(WalletService);
  protected readonly contacts = inject(ContactsService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly maxLength = MAX_PETNAME_LENGTH;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly copied = signal(false);
  /** Address whose name is being edited. */
  protected readonly editing = signal<string | null>(null);
  /** Address being removed, awaiting confirmation. */
  protected readonly removing = signal<string | null>(null);

  /** Scanning this with a phone camera opens the app with the address filled in. */
  protected readonly shareLink = computed(() => {
    const address = this.wallet.publicKey();
    return address ? `${location.origin}/contacts?add=${address}` : null;
  });

  protected address = '';
  protected petname = '';
  protected newName = '';
  /** True when the address came from a link or QR code rather than being typed. */
  protected readonly fromLink = signal(false);

  constructor() {
    const shared = this.route.snapshot.queryParamMap.get('add');
    if (shared) {
      this.address = shared;
      this.fromLink.set(true);
    }
    effect(() => {
      if (this.auth.authenticated()) {
        this.contacts.ensureLoaded().catch((e) => this.error.set(describe(e)));
      }
    });
  }

  ngOnInit(): void {
    // Opened straight from a shared link: load the wallet here so you can sign in without
    // leaving the page (and losing the address).
    if (this.wallet.state() === 'unknown') {
      this.wallet.load().catch((e) => this.error.set(describe(e)));
    }
  }

  protected signIn(): Promise<void> {
    return this.run(async () => {
      await this.auth.login();
    });
  }

  protected short(address: string): string {
    return `${address.slice(0, 8)}…${address.slice(-4)}`;
  }

  protected async copyLink(): Promise<void> {
    const link = this.shareLink();
    if (link) {
      await navigator.clipboard?.writeText(link);
      this.copied.set(true);
    }
  }

  protected add(): Promise<void> {
    return this.run(async () => {
      const contact = await this.contacts.add(this.address, this.petname);
      this.notice.set(`Added “${contact.petname}”.`);
      this.address = '';
      this.petname = '';
      this.fromLink.set(false);
      if (this.route.snapshot.queryParamMap.has('add')) {
        await this.router.navigate([], { queryParams: {}, replaceUrl: true });
      }
    });
  }

  protected startEdit(contact: Contact): void {
    this.editing.set(contact.address);
    this.removing.set(null);
    this.newName = contact.petname;
  }

  protected saveName(contact: Contact): Promise<void> {
    return this.run(async () => {
      await this.contacts.rename(contact.address, this.newName);
      this.editing.set(null);
    });
  }

  protected remove(contact: Contact): Promise<void> {
    return this.run(async () => {
      await this.contacts.remove(contact.address);
      this.removing.set(null);
      this.notice.set(`Removed “${contact.petname}”.`);
    });
  }

  private async run(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      await action();
    } catch (e) {
      this.error.set(describe(e));
    } finally {
      this.busy.set(false);
    }
  }
}

function describe(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) {
      return 'The server is unreachable.';
    }
    return (e.error as { detail?: string } | null)?.detail ?? `Request failed (${e.status}).`;
  }
  return e instanceof Error ? e.message : 'Something went wrong.';
}
