import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AdminService, Dashboard } from './admin.service';
import { LettersChart } from './letters-chart';

/** Everything the server can count, on one page. */
@Component({
  selector: 'app-admin-dashboard',
  imports: [DatePipe, DecimalPipe, LettersChart, RouterLink],
  templateUrl: './admin-dashboard.html',
  styleUrl: './admin-dashboard.scss',
})
export class AdminDashboard implements OnInit {
  private readonly admin = inject(AdminService);

  protected readonly data = signal<Dashboard | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly loading = signal(false);

  protected readonly rejections = computed(() =>
    Object.entries(this.data()?.rateLimits.rejections ?? {}).sort((a, b) => b[1] - a[1]),
  );

  ngOnInit(): Promise<void> {
    return this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.data.set(await this.admin.dashboard());
    } catch {
      this.error.set('The dashboard could not be loaded.');
    } finally {
      this.loading.set(false);
    }
  }

  /** Share of `part` in `whole`, as a whole percentage; null when there is nothing to share. */
  protected percent(part: number, whole: number): number | null {
    return whole > 0 ? Math.round((part / whole) * 100) : null;
  }

  protected rejectRate(accepted: number, rejected: number): string {
    const p = this.percent(rejected, accepted + rejected);
    return p === null ? '–' : `${p}%`;
  }

  protected duration(seconds: number): string {
    const d = Math.floor(seconds / 86400);
    const h = Math.floor((seconds % 86400) / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    return d > 0 ? `${d}d ${h}h` : h > 0 ? `${h}h ${m}m` : `${m}m`;
  }

  protected bytes(n: number): string {
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let i = 0;
    while (n >= 1024 && i < units.length - 1) {
      n /= 1024;
      i++;
    }
    return `${n.toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
  }
}
