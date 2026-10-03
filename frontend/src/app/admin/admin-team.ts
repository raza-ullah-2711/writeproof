import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AdminRole } from './admin.service';

/** Mirrors backend `AdminManagementService.Member`. */
export interface TeamMember {
  publicKey: string;
  role: AdminRole;
  source: 'configuration' | 'granted';
  grantedAt: string | null;
  grantedBy: string | null;
  hasAccount: boolean;
  accountId: string | null;
}

/** Admin and moderator roles (ADMIN only). Every change is audited server-side. */
@Injectable({ providedIn: 'root' })
export class AdminTeam {
  private readonly http = inject(HttpClient);

  members(): Promise<TeamMember[]> {
    return firstValueFrom(this.http.get<TeamMember[]>('/api/admin/admins'));
  }

  grant(address: string, role: AdminRole): Promise<void> {
    return firstValueFrom(
      this.http.put<void>(`/api/admin/admins/${encodeURIComponent(address.trim())}`, { role }),
    );
  }

  revoke(address: string): Promise<void> {
    return firstValueFrom(
      this.http.delete<void>(`/api/admin/admins/${encodeURIComponent(address)}`),
    );
  }
}
