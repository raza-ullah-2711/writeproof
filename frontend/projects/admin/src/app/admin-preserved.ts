import { DatePipe } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';

/** Mirrors backend `PreservedContent.Summary`: metadata only. */
interface Preserved {
  letterHash: string;
  author: string;
  sentAt: string;
  ledgerSeq: number;
  preservedAt: string;
  preserveUntil: string;
  reportId: string | null;
  reportedAt: string | null;
}

/**
 * Child sexual abuse or exploitation removed from open letters, preserved for law enforcement for a
 * year (docs/launch-policies.md, T1). Admins only. Reading a copy's text is audited; record the
 * NCMEC CyberTipline report number once reported.
 */
@Component({
  selector: 'app-admin-preserved',
  imports: [DatePipe, FormsModule],
  template: `
    <p class="hint">
      Content removed as child sexual abuse or exploitation, kept encrypted for one year as the law
      requires, then deleted automatically. Report each item to NCMEC's CyberTipline the same day
      and record the report number here. Showing the text is recorded in the audit log.
    </p>
    @if (error(); as e) {
      <p class="error" role="alert">{{ e }}</p>
    }
    @if (items(); as list) {
      @if (list.length === 0) {
        <p class="hint">Nothing preserved.</p>
      }
      @for (p of list; track p.letterHash) {
        <article class="item">
          <header>
            <span
              >By <code [title]="p.author">{{ p.author.slice(0, 8) }}…</code></span
            >
            <span>preserved {{ p.preservedAt | date: 'yyyy-MM-dd HH:mm' : 'UTC' }}</span>
            <span>until {{ p.preserveUntil | date: 'yyyy-MM-dd' : 'UTC' }}</span>
            <span>ledger #{{ p.ledgerSeq }}</span>
          </header>
          @if (p.reportId) {
            <p class="ok">
              Reported: {{ p.reportId }} ({{ p.reportedAt | date: 'yyyy-MM-dd HH:mm' : 'UTC' }})
            </p>
          } @else {
            <p class="warn">Not yet reported to NCMEC.</p>
          }
          @if (texts()[p.letterHash]; as text) {
            <div class="body">{{ text }}</div>
          } @else {
            <button type="button" (click)="show(p)" [disabled]="busy()">Show text (audited)</button>
          }
          <form class="report" (ngSubmit)="record(p)">
            <input
              [name]="'report-' + p.letterHash"
              [(ngModel)]="reportIds[p.letterHash]"
              placeholder="CyberTipline report number"
              maxlength="100"
            />
            <button type="submit" [disabled]="busy() || !(reportIds[p.letterHash] ?? '').trim()">
              Record report
            </button>
          </form>
        </article>
      }
    } @else if (!error()) {
      <p>Loading&hellip;</p>
    }
  `,
  styles: `
    .hint {
      color: var(--text-secondary);
      font-size: 0.875rem;
    }
    .item {
      border: 1px solid var(--border);
      border-radius: 6px;
      padding: 0.75rem;
      margin-bottom: 0.75rem;
      font-size: 0.875rem;
    }
    header {
      display: flex;
      flex-wrap: wrap;
      gap: 0.75rem;
      color: var(--text-secondary);
    }
    .body {
      white-space: pre-wrap;
      padding: 0.5rem;
      background: var(--paper);
      border-left: 3px solid var(--paper-border);
      margin: 0.5rem 0;
    }
    .ok {
      color: var(--good-text);
    }
    .warn {
      color: var(--warning-text);
    }
    .error {
      color: var(--critical-text);
    }
    .report {
      display: flex;
      gap: 0.5rem;
      margin-top: 0.5rem;
    }
    input {
      font: inherit;
      flex: 1;
    }
  `,
})
export class AdminPreserved implements OnInit {
  private readonly http = inject(HttpClient);

  protected readonly items = signal<Preserved[] | null>(null);
  protected readonly texts = signal<Record<string, string>>({});
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected reportIds: Record<string, string> = {};

  async ngOnInit(): Promise<void> {
    await this.run(async () =>
      this.items.set(await firstValueFrom(this.http.get<Preserved[]>('/api/admin/preserved'))),
    );
  }

  protected show(p: Preserved): Promise<void> {
    return this.run(async () => {
      const copy = await firstValueFrom(
        this.http.get<{ body: string }>(`/api/admin/preserved/${encodeURIComponent(p.letterHash)}`),
      );
      this.texts.update((all) => ({ ...all, [p.letterHash]: copy.body }));
    });
  }

  protected record(p: Preserved): Promise<void> {
    return this.run(async () => {
      const updated = await firstValueFrom(
        this.http.post<Preserved>(
          `/api/admin/preserved/${encodeURIComponent(p.letterHash)}/report`,
          {
            reportId: (this.reportIds[p.letterHash] ?? '').trim(),
          },
        ),
      );
      this.items.update(
        (list) => list?.map((i) => (i.letterHash === p.letterHash ? updated : i)) ?? null,
      );
      this.reportIds[p.letterHash] = '';
    });
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
          : 'Something went wrong.',
      );
    } finally {
      this.busy.set(false);
    }
  }
}
