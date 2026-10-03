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

/** Mirrors backend `ModerationService.Case`. `body` is null once removed. */
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
}

/** Moderation API (moderators and admins). Every decision is audited server-side. */
@Injectable({ providedIn: 'root' })
export class Moderation {
  private readonly http = inject(HttpClient);

  queue(): Promise<ModerationCase[]> {
    return firstValueFrom(this.http.get<ModerationCase[]>('/api/admin/moderation/queue'));
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
