import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AuthService } from './auth.service';

export interface Suspension {
  since: string;
  reason: string;
}

/** Whether an admin suspended this account (it can still sign in and read, but not send). */
@Injectable({ providedIn: 'root' })
export class AccountStatusService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  private readonly _suspension = signal<Suspension | null>(null);
  readonly suspension = this._suspension.asReadonly();

  constructor() {
    effect(() => {
      if (this.auth.authenticated()) {
        void this.refresh();
      } else {
        this._suspension.set(null);
      }
    });
  }

  async refresh(): Promise<void> {
    try {
      const { suspension } = await firstValueFrom(
        this.http.get<{ suspension: Suspension | null }>('/api/me/status'),
      );
      this._suspension.set(suspension);
    } catch {
      // Not knowing is fine: sending still fails with the reason if the account is suspended.
    }
  }
}
