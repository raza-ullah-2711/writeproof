import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { SystemStatus } from '../system/system-status.service';

export interface SystemOverview {
  settings: SystemStatus;
  changes: { key: string; updatedAt: string; updatedBy: string | null }[];
  rateLimitsEnabled: boolean;
  rateLimitScale: number;
  rateLimits: {
    name: string;
    method: string;
    path: string;
    capacity: number;
    windowSeconds: number;
    perAccount: boolean;
  }[];
  handwritingThreshold: number;
  scoresExposed: boolean;
  calibrationContributors: number;
  genuineSamples: number;
  forgerySamples: number;
  ledgerSize: number;
  lastCheckpointSize: number | null;
  lastCheckpointAt: string | null;
}

export interface LedgerAudit {
  chainIntact: boolean;
  length: number;
  brokenAt: number | null;
  checkpointsChecked: number;
  rootMismatches: number[];
  badSignatures: number[];
  ok: boolean;
}

/** System controls (ADMIN only). Every change is audited server-side. */
@Injectable({ providedIn: 'root' })
export class SystemAdmin {
  private readonly http = inject(HttpClient);

  overview(): Promise<SystemOverview> {
    return firstValueFrom(this.http.get<SystemOverview>('/api/admin/system'));
  }

  change(changes: Partial<SystemStatus>): Promise<SystemStatus> {
    return firstValueFrom(this.http.patch<SystemStatus>('/api/admin/system/settings', changes));
  }

  publishCheckpoint(): Promise<{ published: boolean; size: number }> {
    return firstValueFrom(
      this.http.post<{ published: boolean; size: number }>(
        '/api/admin/system/ledger/checkpoint',
        null,
      ),
    );
  }

  auditLedger(): Promise<LedgerAudit> {
    return firstValueFrom(this.http.post<LedgerAudit>('/api/admin/system/ledger/audit', null));
  }
}
