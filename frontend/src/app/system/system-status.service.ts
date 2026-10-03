import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** Mirrors backend `SystemSettings.Status`: public, set by admins. */
export interface SystemStatus {
  registrationOpen: boolean;
  sendingEnabled: boolean;
  openLettersEnabled: boolean;
  announcement: string;
}

const ALL_ON: SystemStatus = {
  registrationOpen: true,
  sendingEnabled: true,
  openLettersEnabled: true,
  announcement: '',
};

/**
 * What admins have switched on or paused, so the app can explain a paused feature up front.
 * The server enforces every switch regardless; until this loads, everything reads as on.
 */
@Injectable({ providedIn: 'root' })
export class SystemStatusService {
  private readonly http = inject(HttpClient);
  private readonly _status = signal<SystemStatus>(ALL_ON);
  readonly status = this._status.asReadonly();

  async refresh(): Promise<void> {
    try {
      this._status.set(await firstValueFrom(this.http.get<SystemStatus>('/api/system/status')));
    } catch {
      // Unknown: keep the last known state; the server still refuses anything paused.
    }
  }
}
