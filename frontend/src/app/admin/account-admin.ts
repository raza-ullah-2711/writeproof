import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AdminRole, AuditEntry } from './admin.service';

export interface Suspension {
  since: string;
  reason: string;
}

/** Mirrors backend `AccountAdminService.Summary`: metadata only. */
export interface AccountSummary {
  accountId: string;
  publicKey: string;
  createdAt: string;
  enrolled: boolean;
  canReceiveLetters: boolean;
  backedUp: boolean;
  lettersSent: number;
  lettersReceived: number;
  openLetters: number;
  suspension: Suspension | null;
  role: AdminRole | null;
}

export interface AccountDetail {
  account: AccountSummary;
  lastLetterAt: string | null;
  usesContacts: boolean;
  calibrationContributor: boolean;
  sessionsRevokedAt: string | null;
  history: AuditEntry[];
}

/** Account management (ADMIN only). Every call that changes something is audited server-side. */
@Injectable({ providedIn: 'root' })
export class AccountAdmin {
  private readonly http = inject(HttpClient);

  search(query: string): Promise<AccountSummary[]> {
    const q = query.trim();
    return firstValueFrom(
      this.http.get<AccountSummary[]>('/api/admin/accounts', { params: q ? { query: q } : {} }),
    );
  }

  detail(id: string): Promise<AccountDetail> {
    return firstValueFrom(this.http.get<AccountDetail>(`/api/admin/accounts/${id}`));
  }

  suspend(id: string, reason: string): Promise<void> {
    return firstValueFrom(this.http.post<void>(`/api/admin/accounts/${id}/suspension`, { reason }));
  }

  reinstate(id: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`/api/admin/accounts/${id}/suspension`));
  }

  signOut(id: string): Promise<{ tokensBefore: string }> {
    return firstValueFrom(
      this.http.post<{ tokensBefore: string }>(`/api/admin/accounts/${id}/sign-out`, null),
    );
  }

  resetRateLimits(id: string): Promise<{ buckets: number }> {
    return firstValueFrom(
      this.http.post<{ buckets: number }>(`/api/admin/accounts/${id}/rate-limits/reset`, null),
    );
  }
}
