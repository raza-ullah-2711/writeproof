import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SystemStatus, SystemStatusService } from '@app/system/system-status.service';
import { LedgerAudit, SystemAdmin, SystemOverview } from './system-admin';

type Flag = 'registrationOpen' | 'sendingEnabled' | 'openLettersEnabled';

/** Switches, the announcement, ledger tools and read-only configuration. */
@Component({
  selector: 'app-admin-system',
  imports: [DatePipe, FormsModule],
  templateUrl: './admin-system.html',
  styleUrl: './admin-system.scss',
})
export class AdminSystem implements OnInit {
  private readonly system = inject(SystemAdmin);
  private readonly publicStatus = inject(SystemStatusService);

  protected readonly overview = signal<SystemOverview | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly audit = signal<LedgerAudit | null>(null);
  /** A switch awaiting confirmation. */
  protected readonly pending = signal<{ flag: Flag; on: boolean } | null>(null);
  protected announcement = '';

  protected readonly flags: { flag: Flag; label: string; off: string }[] = [
    {
      flag: 'registrationOpen',
      label: 'New accounts',
      off: 'Nobody can create an account. Existing accounts sign in and restore as usual.',
    },
    {
      flag: 'sendingEnabled',
      label: 'Sending letters',
      off: 'Nobody can send or reply. Everyone can still read.',
    },
    {
      flag: 'openLettersEnabled',
      label: 'Publishing open letters',
      off: 'Nobody can publish. Existing open letters stay readable.',
    },
  ];

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  protected isOn(s: SystemStatus, flag: Flag): boolean {
    return s[flag];
  }

  protected changedAt(o: SystemOverview, key: string): string | null {
    return o.changes.find((c) => c.key === key)?.updatedAt ?? null;
  }

  protected ask(flag: Flag, on: boolean): void {
    this.pending.set({ flag, on });
    this.notice.set(null);
  }

  protected confirm(): Promise<void> {
    const p = this.pending();
    if (!p) {
      return Promise.resolve();
    }
    return this.run(async () => {
      await this.system.change({ [p.flag]: p.on });
      this.pending.set(null);
      await this.load();
      const label = this.flags.find((f) => f.flag === p.flag)?.label ?? p.flag;
      this.notice.set(`${label}: ${p.on ? 'on' : 'paused'}.`);
    });
  }

  protected saveAnnouncement(): Promise<void> {
    return this.run(async () => {
      await this.system.change({ announcement: this.announcement });
      await this.load();
      this.notice.set(
        this.announcement.trim() ? 'Announcement shown to everyone.' : 'Announcement cleared.',
      );
    });
  }

  protected publishCheckpoint(): Promise<void> {
    return this.run(async () => {
      const result = await this.system.publishCheckpoint();
      await this.load();
      this.notice.set(
        result.published
          ? `Published a checkpoint of ${result.size} entries.`
          : result.size === 0
            ? 'The ledger is empty: there is nothing to publish yet.'
            : `Nothing new to publish: the last checkpoint already covers ${result.size} entries.`,
      );
    });
  }

  protected auditLedger(): Promise<void> {
    return this.run(async () => {
      this.audit.set(null);
      this.audit.set(await this.system.auditLedger());
    });
  }

  private async load(): Promise<void> {
    try {
      const o = await this.system.overview();
      this.overview.set(o);
      this.announcement = o.settings.announcement;
      void this.publicStatus.refresh();
    } catch {
      this.error.set('System settings could not be loaded.');
    }
  }

  private async run(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await action();
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse
          ? ((e.error as { detail?: string } | null)?.detail ?? `Request failed (${e.status}).`)
          : 'Something went wrong.',
      );
    } finally {
      this.busy.set(false);
    }
  }
}
