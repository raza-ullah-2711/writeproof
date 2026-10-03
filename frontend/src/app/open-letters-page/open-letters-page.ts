import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import {
  HandwritingApi,
  HandwritingRejection,
  describeRejection,
} from '../handwriting/handwriting-api';
import { HandwritingPad } from '../handwriting/handwriting-pad';
import {
  MAX_OPEN_LETTER_LENGTH,
  OpenLetter,
  OpenLettersService,
} from '../open-letters/open-letters.service';
import { categoryLabel } from '../open-letters/report-categories';
import { SystemStatusService } from '../system/system-status.service';

@Component({
  selector: 'app-open-letters-page',
  imports: [FormsModule, RouterLink, HandwritingPad],
  templateUrl: './open-letters-page.html',
  styleUrl: './open-letters-page.scss',
})
export class OpenLettersPage {
  protected readonly auth = inject(AuthService);
  private readonly openLetters = inject(OpenLettersService);
  private readonly handwriting = inject(HandwritingApi);
  protected readonly system = inject(SystemStatusService);

  protected readonly pad = viewChild(HandwritingPad);
  protected readonly enrolled = signal<boolean | null>(null);
  protected readonly mine = signal<OpenLetter[] | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly published = signal<OpenLetter | null>(null);
  protected readonly copied = signal<string | null>(null);
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
    return this.run(async () => {
      try {
        const letter = await this.openLetters.publish(this.body, signature);
        this.published.set(letter);
        this.body = '';
        this.understood = false;
        await this.loadMine();
      } finally {
        this.pad()?.clear();
      }
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
