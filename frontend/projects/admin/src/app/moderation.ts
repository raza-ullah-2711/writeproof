import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ReportCategory } from '@app/open-letters/report-categories';

export interface Report {
  id: number;
  category: ReportCategory;
  note: string | null;
  createdAt: string;
  fromAccount: boolean;
  resolution: 'dismissed' | 'removed' | null;
}

/** A takedown still on hold: hidden, text kept until `deleteAfter` unless appealed. */
export interface Hold {
  category: ReportCategory;
  heldAt: string;
  deleteAfter: string;
  appeal: string | null;
  appealedAt: string | null;
}

/** Mirrors backend `ModerationService.Case`. `body` is null once removed (moderators see it during a hold). */
export interface ModerationCase {
  letterHash: string;
  author: string;
  sentAt: string;
  body: string | null;
  ledgerSeq: number;
  openReportsByCategory: Partial<Record<ReportCategory, number>>;
  openReports: number;
  firstReportedAt: string | null;
  reports: Report[];
  removal: { category: ReportCategory; at: string; by: string } | null;
  hold: Hold | null;
}

/** Moderation API (moderators and admins). Every decision is audited server-side. */
@Injectable({ providedIn: 'root' })
export class Moderation {
  private readonly http = inject(HttpClient);

  queue(): Promise<ModerationCase[]> {
    return firstValueFrom(this.http.get<ModerationCase[]>('/api/admin/moderation/queue'));
  }

  /** Takedowns their authors appealed, oldest first. */
  appeals(): Promise<ModerationCase[]> {
    return firstValueFrom(this.http.get<ModerationCase[]>('/api/admin/moderation/appeals'));
  }

  /** Reverses a takedown still on hold (grants an appeal). */
  restore(hash: string, note: string): Promise<ModerationCase> {
    return firstValueFrom(
      this.http.post<ModerationCase>(
        `/api/admin/moderation/letters/${encodeURIComponent(hash)}/restore`,
        { note },
      ),
    );
  }

  /** Confirms a takedown still on hold (rejects an appeal): the text is deleted now. */
  uphold(hash: string, note: string): Promise<ModerationCase> {
    return firstValueFrom(
      this.http.post<ModerationCase>(
        `/api/admin/moderation/letters/${encodeURIComponent(hash)}/uphold`,
        { note },
      ),
    );
  }

  letter(hash: string): Promise<ModerationCase> {
    return firstValueFrom(
      this.http.get<ModerationCase>(`/api/admin/moderation/letters/${encodeURIComponent(hash)}`),
    );
  }

  dismiss(hash: string, note: string): Promise<void> {
    return firstValueFrom(
      this.http.post<void>(`/api/admin/moderation/letters/${encodeURIComponent(hash)}/dismiss`, {
        note,
      }),
    );
  }

  remove(hash: string, category: ReportCategory, note: string): Promise<ModerationCase> {
    return firstValueFrom(
      this.http.post<ModerationCase>(
        `/api/admin/moderation/letters/${encodeURIComponent(hash)}/remove`,
        { category, note },
      ),
    );
  }
}
