import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { Letter, LettersService, MAX_BODY_LENGTH, OpenedLetter } from '../letters/letters.service';
import { WalletService } from '../wallet/wallet.service';

type Box = 'inbox' | 'sent';

@Component({
  selector: 'app-letters-page',
  imports: [FormsModule, RouterLink],
  templateUrl: './letters-page.html',
  styleUrl: './letters-page.scss',
})
export class LettersPage {
  protected readonly auth = inject(AuthService);
  protected readonly wallet = inject(WalletService);
  private readonly letters = inject(LettersService);

  protected readonly maxLength = MAX_BODY_LENGTH;
  protected readonly box = signal<Box>('inbox');
  protected readonly list = signal<Letter[] | null>(null);
  protected readonly opened = signal<Record<string, OpenedLetter | 'opening'>>({});
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly copied = signal(false);

  protected recipient = '';
  protected body = '';

  constructor() {
    effect(() => {
      if (this.auth.authenticated()) {
        void this.show(this.box());
      }
    });
  }

  protected short(address: string): string {
    return address.length > 14 ? `${address.slice(0, 8)}…${address.slice(-4)}` : address;
  }

  protected async copyAddress(): Promise<void> {
    const address = this.wallet.publicKey();
    if (address) {
      await navigator.clipboard?.writeText(address);
      this.copied.set(true);
    }
  }

  protected selectBox(box: Box): void {
    this.box.set(box);
  }

  protected async show(box: Box): Promise<void> {
    await this.run(async () => {
      this.list.set(null);
      this.list.set(box === 'inbox' ? await this.letters.inbox() : await this.letters.sent());
    });
  }

  protected send(): Promise<void> {
    this.notice.set(null);
    return this.run(async () => {
      const letter = await this.letters.send(this.recipient.trim(), this.body);
      this.body = '';
      this.notice.set(`Sealed and recorded as ledger entry #${letter.ledger.seq}.`);
      if (this.box() === 'sent') {
        await this.show('sent');
      } else {
        this.box.set('sent');
      }
    });
  }

  protected async open(letter: Letter): Promise<void> {
    this.opened.update((o) => ({ ...o, [letter.letterId]: 'opening' }));
    try {
      const result = await this.letters.open(letter);
      this.opened.update((o) => ({ ...o, [letter.letterId]: result }));
    } catch (e) {
      this.opened.update(({ [letter.letterId]: _, ...rest }) => rest);
      this.error.set(describe(e));
    }
  }

  protected result(letter: Letter): OpenedLetter | 'opening' | undefined {
    return this.opened()[letter.letterId];
  }

  protected asOpened(value: OpenedLetter | 'opening' | undefined): OpenedLetter | null {
    return value && value !== 'opening' ? value : null;
  }

  private async run(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
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
    if (e.status === 404) {
      return 'No account with that address.';
    }
    return (e.error as { detail?: string } | null)?.detail ?? `Request failed (${e.status}).`;
  }
  return e instanceof Error ? e.message : 'Something went wrong.';
}
