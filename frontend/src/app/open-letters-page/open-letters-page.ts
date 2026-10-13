import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  computed,
  effect,
  inject,
  signal,
  viewChild,
  viewChildren,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import {
  HandwritingApi,
  HandwritingRejection,
  describeRejection,
} from '../handwriting/handwriting-api';
import { HandwritingPad } from '../handwriting/handwriting-pad';
import { PageComposer } from '../pages/page-composer';
import {
  MAX_OPEN_LETTER_LENGTH,
  OpenLetter,
  OpenLettersService,
} from '../open-letters/open-letters.service';
import { categoryLabel } from '../open-letters/report-categories';
import { SystemStatusService } from '../system/system-status.service';

@Component({
  selector: 'app-open-letters-page',
  imports: [DatePipe, FormsModule, RouterLink, HandwritingPad, PageComposer],
  templateUrl: './open-letters-page.html',
  styleUrl: './open-letters-page.scss',
})
export class OpenLettersPage {
  protected readonly auth = inject(AuthService);
  private readonly openLetters = inject(OpenLettersService);
  private readonly handwriting = inject(HandwritingApi);
  protected readonly system = inject(SystemStatusService);

  /** The signature that seals the letter. */
  protected readonly pad = viewChild('signaturePad', { read: HandwritingPad });
  /** Writing the letter by hand (docs/handwritten-pages.md). */
  protected readonly composer = viewChild(PageComposer);
  protected readonly mode = signal<'typed' | 'handwritten'>('typed');
  /** Readers may watch the pages being written: the author's choice, off by default. */
  protected replay = false;
  protected readonly enrolled = signal<boolean | null>(null);
  protected readonly mine = signal<OpenLetter[] | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly published = signal<OpenLetter | null>(null);
  protected readonly copied = signal<string | null>(null);
  /** Appeal drafts, by letter hash. */
  protected readonly appealText = signal<Record<string, string>>({});
  protected readonly maxLength = MAX_OPEN_LETTER_LENGTH;
  protected readonly label = categoryLabel;
  /** Publishing is permanent and public, so it takes an explicit confirmation. */
  protected understood = false;
  protected body = '';

  constructor() {
    effect(() => {
      if (this.auth.authenticated()) {
        this.handwriting.enrolment().then(
          (e) => this.enrolled.set(e.enrolled),
          () => this.enrolled.set(null),
        );
        void this.loadMine();
      }
    });
  }

  protected link(letter: OpenLetter): string {
    return `${location.origin}/open/${letter.letterHash}`;
  }

  protected async copy(letter: OpenLetter): Promise<void> {
    await navigator.clipboard?.writeText(this.link(letter));
    this.copied.set(letter.letterHash);
  }

  protected publish(): Promise<void> {
    const signature = this.pad()?.sample();
    if (!signature) {
      this.error.set('Sign the letter by hand first.');
      return Promise.resolve();
    }
    const handwritten = this.mode() === 'handwritten';
    const pages = handwritten ? (this.composer()?.pages() ?? []) : [];
    if (handwritten && pages.length === 0) {
      this.error.set('Write on at least one page first.');
      return Promise.resolve();
    }
    return this.run(async () => {
      try {
        const letter = handwritten
          ? await this.openLetters.publish(this.body, signature, { pages, replay: this.replay })
          : await this.openLetters.publish(this.body, signature);
        this.published.set(letter);
        this.body = '';
        this.understood = false;
        this.replay = false;
        if (handwritten) {
          this.composer()?.reset();
        }
        await this.loadMine();
      } finally {
        this.pad()?.clear();
      }
    });
  }

  protected setAppealText(letterHash: string, text: string): void {
    this.appealText.update((all) => ({ ...all, [letterHash]: text }));
  }

  protected sendAppeal(letterHash: string): Promise<void> {
    return this.run(async () => {
      await this.openLetters.appeal(letterHash, this.appealText()[letterHash] ?? '');
      this.mine.set(await this.openLetters.mine());
    });
  }

  private loadMine(): Promise<void> {
    return this.run(async () => this.mine.set(await this.openLetters.mine()));
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
    const problem = e.error as HandwritingRejection | null;
    if (e.status === 422 && Array.isArray(problem?.livenessFlags)) {
      return describeRejection(problem);
    }
    return (e.error as { detail?: string } | null)?.detail ?? `Request failed (${e.status}).`;
  }
  return e instanceof Error ? e.message : 'Something went wrong.';
}
