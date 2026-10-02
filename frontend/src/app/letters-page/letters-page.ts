import { HttpErrorResponse } from '@angular/common/http';
import { NgTemplateOutlet } from '@angular/common';
import { Component, effect, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { ContactsService } from '../contacts/contacts.service';
import {
  HandwritingApi,
  HandwritingRejection,
  describeRejection,
} from '../handwriting/handwriting-api';
import { HandwritingPad } from '../handwriting/handwriting-pad';
import { HandwritingSample } from '../handwriting/handwriting-sample';
import { SignatureView } from '../handwriting/signature-view';
import {
  Letter,
  LettersService,
  MAX_BODY_LENGTH,
  OpenedLetter,
  ThreadSummary,
  checkThread,
} from '../letters/letters.service';
import { WalletService } from '../wallet/wallet.service';

type Box = 'inbox' | 'sent' | 'threads';

/** An open conversation: its letters oldest first, and whether they link up. */
interface Conversation {
  threadId: string;
  counterpart: string;
  letters: Letter[];
  problem: string | null;
}

@Component({
  selector: 'app-letters-page',
  imports: [FormsModule, NgTemplateOutlet, RouterLink, HandwritingPad, SignatureView],
  templateUrl: './letters-page.html',
  styleUrl: './letters-page.scss',
})
export class LettersPage {
  protected readonly auth = inject(AuthService);
  protected readonly wallet = inject(WalletService);
  private readonly letters = inject(LettersService);
  private readonly handwriting = inject(HandwritingApi);
  protected readonly contacts = inject(ContactsService);

  protected readonly pad = viewChild(HandwritingPad);
  /** Null while unknown; sending needs enrolled handwriting. */
  protected readonly enrolled = signal<boolean | null>(null);

  protected readonly maxLength = MAX_BODY_LENGTH;
  protected readonly box = signal<Box>('inbox');
  protected readonly list = signal<Letter[] | null>(null);
  protected readonly opened = signal<Record<string, OpenedLetter | 'opening'>>({});
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly copied = signal(false);
  protected readonly threadList = signal<ThreadSummary[] | null>(null);
  protected readonly conversation = signal<Conversation | null>(null);
  /** The letter being answered, while composing a reply. */
  protected readonly replyTo = signal<Letter | null>(null);
  protected readonly tabs: { box: Box; label: string }[] = [
    { box: 'inbox', label: 'Inbox' },
    { box: 'sent', label: 'Sent' },
    { box: 'threads', label: 'Conversations' },
  ];

  protected recipient = '';
  protected body = '';

  constructor() {
    this.recipient = inject(ActivatedRoute).snapshot.queryParamMap.get('to') ?? '';
    effect(() => {
      if (this.auth.authenticated()) {
        // Names are a convenience here: letters still work if the book can't be loaded.
        this.contacts.ensureLoaded().catch(() => undefined);
      }
    });
    effect(() => {
      if (this.auth.authenticated()) {
        void this.show(this.box());
      }
    });
    effect(() => {
      if (this.auth.authenticated()) {
        this.handwriting.enrolment().then(
          (e) => this.enrolled.set(e.enrolled),
          () => this.enrolled.set(null),
        );
      }
    });
  }

  /** Your name for an address, if it is a contact. */
  protected petname(address: string): string | null {
    return this.contacts.petname(address.trim());
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
    this.conversation.set(null);
    this.box.set(box);
  }

  protected async show(box: Box): Promise<void> {
    await this.run(async () => {
      if (box === 'threads') {
        this.threadList.set(null);
        this.threadList.set(await this.letters.threads());
        return;
      }
      this.list.set(null);
      this.list.set(box === 'inbox' ? await this.letters.inbox() : await this.letters.sent());
    });
  }

  /** The other person in a letter's conversation. */
  protected otherParty(letter: Letter): string {
    const me = this.wallet.publicKey();
    return letter.sender.publicKey === me ? letter.recipient.publicKey : letter.sender.publicKey;
  }

  protected openThread(threadId: string, counterpart: string): Promise<void> {
    return this.run(async () => {
      const letters = await this.letters.thread(threadId);
      this.conversation.set({
        threadId,
        counterpart,
        letters,
        problem: checkThread(threadId, letters),
      });
      this.box.set('threads');
    });
  }

  protected closeThread(): void {
    this.conversation.set(null);
    void this.show('threads');
  }

  protected startReply(letter: Letter): void {
    this.replyTo.set(letter);
    this.recipient = this.otherParty(letter);
    this.notice.set(null);
    this.error.set(null);
    queueMicrotask(() =>
      document.querySelector<HTMLTextAreaElement>('textarea[name=body]')?.focus(),
    );
  }

  protected cancelReply(): void {
    this.replyTo.set(null);
  }

  protected send(): Promise<void> {
    this.notice.set(null);
    const signature = this.pad()?.sample();
    if (!signature) {
      this.error.set('Sign the letter by hand first.');
      return Promise.resolve();
    }
    return this.run(async () => {
      try {
        await this.sendSigned(signature);
      } finally {
        // A signature is single-use: success or failure, the next attempt needs a fresh one.
        this.pad()?.clear();
      }
    });
  }

  private async sendSigned(signature: HandwritingSample): Promise<void> {
    const to = this.recipient.trim();
    const parent = this.replyTo() ?? undefined;
    const letter = await this.letters.send(to, this.body, signature, parent);
    this.body = '';
    this.replyTo.set(null);
    const name = this.petname(to);
    this.notice.set(
      `Sealed${name ? ` for ${name}` : ''} and recorded as ledger entry #${letter.ledger.seq}.`,
    );
    const open = this.conversation();
    if (open && open.threadId === letter.threadId) {
      const letters = await this.letters.thread(open.threadId);
      this.conversation.set({ ...open, letters, problem: checkThread(open.threadId, letters) });
    } else if (this.box() === 'sent') {
      await this.show('sent');
    } else {
      this.box.set('sent');
    }
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
    const problem = e.error as HandwritingRejection | null;
    if (e.status === 422 && Array.isArray(problem?.livenessFlags)) {
      return describeRejection(problem);
    }
    return (e.error as { detail?: string } | null)?.detail ?? `Request failed (${e.status}).`;
  }
  return e instanceof Error ? e.message : 'Something went wrong.';
}
