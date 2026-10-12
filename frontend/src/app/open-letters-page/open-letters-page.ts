import { DatePipe, DecimalPipe } from '@angular/common';
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
import { HandwritingSample, pointCount } from '../handwriting/handwriting-sample';
import {
  INKS,
  InkName,
  MAX_PAGES,
  MAX_PAGE_POINTS,
  NIBS,
  Nib,
  PAGE_HEIGHT,
  PAGE_WIDTH,
  Pen,
  pageFromSample,
} from '../pages/page-format';
import {
  MAX_OPEN_LETTER_LENGTH,
  OpenLetter,
  OpenLettersService,
} from '../open-letters/open-letters.service';
import { categoryLabel } from '../open-letters/report-categories';
import { SystemStatusService } from '../system/system-status.service';

@Component({
  selector: 'app-open-letters-page',
  imports: [DatePipe, DecimalPipe, FormsModule, RouterLink, HandwritingPad],
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
  /** Handwritten pages, in order (docs/handwritten-pages.md). */
  protected readonly pagePads = viewChildren('pagePad', { read: HandwritingPad });
  protected readonly mode = signal<'typed' | 'handwritten'>('typed');
  protected readonly nibs = NIBS;
  protected readonly inks = INKS;
  protected readonly nib = signal<Nib>('fountain');
  protected readonly ink = signal<InkName>('ink');
  protected readonly pen = computed<Pen>(() => ({ nib: this.nib(), ink: this.ink() }));
  private nextPageId = 1;
  protected readonly pageIds = signal<number[]>([0]);
  protected readonly current = signal(0);
  protected readonly currentPad = computed(() => this.pagePads()[this.current()]);
  private readonly points = signal<Record<number, number>>({});
  protected readonly currentPoints = computed(
    () => this.points()[this.pageIds()[this.current()]] ?? 0,
  );
  protected readonly hasWriting = computed(() => this.pagePads().some((p) => !p.empty()));
  protected readonly maxPages = MAX_PAGES;
  protected readonly maxPoints = MAX_PAGE_POINTS;
  protected readonly pageWidth = PAGE_WIDTH;
  protected readonly pageHeight = PAGE_HEIGHT;
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

  protected addPage(): void {
    if (this.pageIds().length >= MAX_PAGES) {
      return;
    }
    this.pageIds.update((ids) => [...ids, this.nextPageId++]);
    this.current.set(this.pageIds().length - 1);
  }

  protected removePage(): void {
    const index = this.current();
    const id = this.pageIds()[index];
    this.pageIds.update((ids) => ids.filter((i) => i !== id));
    this.points.update(({ [id]: _, ...rest }) => rest);
    this.current.set(Math.max(0, index - 1));
  }

  protected clearPage(): void {
    this.currentPad()?.clear();
    this.points.update((all) => ({ ...all, [this.pageIds()[this.current()]]: 0 }));
  }

  protected countPoints(id: number, sample: HandwritingSample): void {
    this.points.update((all) => ({ ...all, [id]: pointCount(sample) }));
  }

  protected publish(): Promise<void> {
    const signature = this.pad()?.sample();
    if (!signature) {
      this.error.set('Sign the letter by hand first.');
      return Promise.resolve();
    }
    const handwritten = this.mode() === 'handwritten';
    const pages = handwritten
      ? this.pagePads()
          .map((p) => p.sample())
          .filter((s): s is HandwritingSample => s !== null)
          .map((s) => pageFromSample(s, this.pen()))
      : [];
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
          this.pagePads().forEach((p) => p.clear());
          this.pageIds.set([this.nextPageId++]);
          this.current.set(0);
          this.points.set({});
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
