import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  REPORT_CATEGORIES,
  ReportCategory,
  categoryLabel,
} from '@app/open-letters/report-categories';
import { Moderation, ModerationCase } from './moderation';

type Action = 'dismiss' | 'remove' | 'restore' | 'uphold';

interface Decision {
  hash: string;
  action: Action;
}

const DONE: Record<Action, string> = {
  dismiss: 'Reports dismissed; the letter stays up.',
  remove:
    'Letter hidden. Its text is deleted after 14 days unless the author appeals and it is restored.',
  restore: 'Letter restored: it is public again.',
  uphold: 'Takedown upheld. The text is deleted; the record stays on the ledger.',
};

/** The moderation queue: reported open letters, and a lookup for any letter by its link. */
@Component({
  selector: 'app-admin-moderation',
  imports: [DatePipe, FormsModule, NgTemplateOutlet, RouterLink],
  templateUrl: './admin-moderation.html',
  styleUrl: './admin-moderation.scss',
})
export class AdminModeration implements OnInit {
  private readonly moderation = inject(Moderation);

  protected readonly queue = signal<ModerationCase[] | null>(null);
  protected readonly appeals = signal<ModerationCase[]>([]);
  protected readonly looked = signal<ModerationCase | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly deciding = signal<Decision | null>(null);
  protected readonly categories = REPORT_CATEGORIES;
  protected readonly label = categoryLabel;
  protected lookup = '';
  protected category: ReportCategory = 'spam';
  protected note = '';

  ngOnInit(): Promise<void> {
    return this.load();
  }

  protected categoriesOf(c: ModerationCase): [string, number][] {
    return Object.entries(c.openReportsByCategory) as [string, number][];
  }

  protected decide(c: ModerationCase, action: Action): void {
    this.deciding.set({ hash: c.letterHash, action });
    this.note = '';
    // Default to the category most reporters chose.
    const top = this.categoriesOf(c).sort((a, b) => b[1] - a[1])[0];
    this.category = (top?.[0] as ReportCategory) ?? 'other';
    this.notice.set(null);
    this.error.set(null);
  }

  protected async confirm(): Promise<void> {
    const d = this.deciding();
    if (!d) {
      return;
    }
    await this.run(async () => {
      const childSafety = d.action === 'remove' && this.category === 'child_safety';
      if (d.action === 'dismiss') {
        await this.moderation.dismiss(d.hash, this.note);
      } else if (d.action === 'remove') {
        await this.moderation.remove(d.hash, this.category, this.note);
      } else if (d.action === 'restore') {
        await this.moderation.restore(d.hash, this.note);
      } else {
        await this.moderation.uphold(d.hash, this.note);
      }
      this.deciding.set(null);
      await this.load();
      if (this.looked()?.letterHash === d.hash) {
        this.looked.set(await this.moderation.letter(d.hash));
      }
      this.notice.set(
        childSafety
          ? 'Removed and preserved for law enforcement; the author is suspended. Report it to NCMEC today, then record the report number under Preserved.'
          : DONE[d.action],
      );
    });
  }

  /** Accepts an open letter link (…/open/<hash>) or the hash itself. */
  protected find(): Promise<void> {
    const hash = this.lookup.trim().split('/open/').pop()?.split(/[?#]/)[0] ?? '';
    return this.run(async () => {
      this.looked.set(null);
      try {
        this.looked.set(await this.moderation.letter(hash));
      } catch (e) {
        if (e instanceof HttpErrorResponse && (e.status === 404 || e.status === 400)) {
          throw new Error('No open letter matches that link.');
        }
        throw e;
      }
    });
  }

  protected short(key: string): string {
    return `${key.slice(0, 8)}…${key.slice(-4)}`;
  }

  private async load(): Promise<void> {
    try {
      this.queue.set(await this.moderation.queue());
      this.appeals.set(await this.moderation.appeals());
    } catch {
      this.error.set('The moderation queue could not be loaded.');
    }
  }

  private async run(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await action();
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse
          ? ((e.error as { detail?: string } | null)?.detail ?? `Request failed (${e.status}).`)
          : e instanceof Error
            ? e.message
            : 'Something went wrong.',
      );
    } finally {
      this.busy.set(false);
    }
  }
}
