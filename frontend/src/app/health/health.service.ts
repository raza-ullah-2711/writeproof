import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';

export type BackendStatus = 'checking' | 'up' | 'down';

interface HealthResponse {
  status: string;
}

@Injectable({ providedIn: 'root' })
export class HealthService {
  private readonly http = inject(HttpClient);

  readonly status = signal<BackendStatus>('checking');

  check(): void {
    this.status.set('checking');
    this.http.get<HealthResponse>('/actuator/health').subscribe({
      next: (res) => this.status.set(res.status === 'UP' ? 'up' : 'down'),
      error: () => this.status.set('down'),
    });
  }
}
