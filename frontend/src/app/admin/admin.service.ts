import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../auth/auth.service';

export type AdminRole = 'ADMIN' | 'MODERATOR';

export interface Day {
  date: string;
  sealed: number;
  open: number;
}

/** Mirrors backend `DashboardService.Dashboard`: counts only, never content. */
export interface Dashboard {
  generatedAt: string;
  accounts: {
    total: number;
    new7d: number;
    new30d: number;
    enrolled: number;
    canReceiveLetters: number;
    backedUp: number;
    withContacts: number;
    calibrationContributors: number;
  };
  letters: {
    sealed: number;
    sealed24h: number;
    sealed7d: number;
    replies: number;
    open: number;
    open7d: number;
    last30Days: Day[];
  };
  ledger: {
    size: number;
    lastCheckpointSize: number | null;
    lastCheckpointAt: string | null;
    unpublished: number;
  };
  handwriting: {
    threshold: number;
    checksAccepted: number;
    checksRejected: number;
    lettersAccepted: number;
    lettersRejected: number;
  };
  rateLimits: { rejections: Record<string, number> };
  system: { version: string; startedAt: string; uptimeSeconds: number; databaseBytes: number };
}

export interface AuditEntry {
  id: number;
  at: string;
  actor: string;
  actorRole: AdminRole;
  action: string;
  target: string | null;
  detail: Record<string, unknown>;
}

/**
 * The signed-in account's admin role, and the admin API. The server decides every access on
 * every request; the role here only decides what the app shows.
 */
@Injectable({ providedIn: 'root' })
export class AdminService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  private readonly _role = signal<AdminRole | null>(null);
  /** Null for ordinary accounts (and while signed out). */
  readonly role = this._role.asReadonly();

  constructor() {
    effect(() => {
      if (this.auth.authenticated()) {
        this.refresh().catch(() => this._role.set(null));
      } else {
        this._role.set(null);
      }
    });
  }

  async refresh(): Promise<AdminRole | null> {
    const { role } = await firstValueFrom(
      this.http.get<{ role: AdminRole | null }>('/api/admin/me'),
    );
    this._role.set(role);
    return role;
  }

  dashboard(): Promise<Dashboard> {
    return firstValueFrom(this.http.get<Dashboard>('/api/admin/dashboard'));
  }

  audit(before?: number): Promise<AuditEntry[]> {
    const params: Record<string, number> = { limit: 50 };
    if (before !== undefined) {
      params['before'] = before;
    }
    return firstValueFrom(this.http.get<AuditEntry[]>('/api/admin/audit', { params }));
  }
}
