import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { SystemStatusService } from '../system/system-status.service';
import { Component, OnInit, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../auth/auth.service';
import { BackupService, BackupStatus } from '../wallet/backup.service';
import { WalletService } from '../wallet/wallet.service';

@Component({
  selector: 'app-home',
  imports: [FormsModule, DatePipe],
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class Home implements OnInit {
  protected readonly system = inject(SystemStatusService);
  protected readonly wallet = inject(WalletService);
  protected readonly auth = inject(AuthService);

  private readonly backups = inject(BackupService);

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly backup = signal<BackupStatus | null>(null);
  /** A freshly created recovery code, shown once until dismissed. */
  protected readonly newCode = signal<string | null>(null);
  protected readonly copied = signal(false);
  protected recoveryCode = '';
  /** Account deletion: the confirmation form is open, and what must be typed to confirm. */
  protected readonly deleting = signal(false);
  protected readonly deleted = signal(false);
  protected readonly confirmPhrase = 'delete my account';
  protected deleteConfirmation = '';

  constructor() {
    effect(() => {
      if (this.auth.account()) {
        this.backups.status().then(
          (status) => this.backup.set(status),
          () => this.backup.set(null),
        );
      } else {
        this.backup.set(null);
      }
    });
  }

  async ngOnInit(): Promise<void> {
    await this.run(() => this.wallet.load());
  }

  protected createWallet(): Promise<void> {
    return this.run(async () => {
      await this.wallet.create();
      await this.auth.register();
      await this.auth.login();
    });
  }

  protected restore(): Promise<void> {
    return this.run(async () => {
      await this.backups.restore(this.recoveryCode);
      this.recoveryCode = '';
      await this.auth.login();
    });
  }

  protected createBackup(): Promise<void> {
    return this.run(async () => {
      this.copied.set(false);
      this.newCode.set(await this.backups.create());
      this.backup.set(await this.backups.status());
    });
  }

  protected async copyCode(code: string): Promise<void> {
    await navigator.clipboard?.writeText(code);
    this.copied.set(true);
  }

  protected deleteAccount(): Promise<void> {
    return this.run(async () => {
      await this.auth.deleteAccount();
      this.deleting.set(false);
      this.deleteConfirmation = '';
      this.deleted.set(true);
    });
  }

  protected signIn(): Promise<void> {
    return this.run(async () => {
      try {
        await this.auth.login();
      } catch (e) {
        // A wallet created while the server was unreachable was never registered.
        if (!(e instanceof HttpErrorResponse && e.status === 404)) {
          throw e;
        }
        await this.auth.register();
        await this.auth.login();
      }
    });
  }

  private async run(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await action();
    } catch (e) {
      this.error.set(describe(e));
    } finally {
      this.busy.set(false);
    }
  }
}

function describe(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) {
      return 'The server is unreachable.';
    }
    return (e.error as { detail?: string } | null)?.detail ?? `Request failed (${e.status}).`;
  }
  return e instanceof Error ? e.message : 'Something went wrong.';
}
