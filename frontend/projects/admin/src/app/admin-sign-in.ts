import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { BackupService } from '@app/wallet/backup.service';
import { WalletService } from '@app/wallet/wallet.service';
import { AdminAuth } from './admin-auth.service';

/**
 * Sign-in for the admin app. Browsers keep a wallet per origin, so this site has its own copy:
 * restored once from the wallet's recovery code, then used to sign in.
 */
@Component({
  selector: 'app-admin-sign-in',
  imports: [FormsModule],
  template: `
    <section class="sign-in">
      <h2>Sign in</h2>
      @switch (wallet.state()) {
        @case ('ready') {
          <p>
            Wallet <code>{{ wallet.publicKey() }}</code> is on this device.
          </p>
          <button type="button" (click)="signIn()" [disabled]="busy()">Sign in</button>
        }
        @case ('unknown') {
          <p>Loading&hellip;</p>
        }
        @default {
          <p>
            The admin site keeps its own copy of your wallet. Enter the recovery code of your admin
            wallet to restore it here (create one on the Wallet page of the app).
          </p>
          <label>
            Recovery code
            <input
              name="code"
              autocomplete="off"
              spellcheck="false"
              [(ngModel)]="recoveryCode"
              [disabled]="busy()"
            />
          </label>
          <button type="button" (click)="restore()" [disabled]="busy() || !recoveryCode.trim()">
            Restore and sign in
          </button>
        }
      }
      @if (error(); as e) {
        <p class="error" role="alert">{{ e }}</p>
      }
    </section>
  `,
})
export class AdminSignIn implements OnInit {
  protected readonly wallet = inject(WalletService);
  private readonly auth = inject(AdminAuth);
  private readonly backups = inject(BackupService);
  private readonly router = inject(Router);

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected recoveryCode = '';

  async ngOnInit(): Promise<void> {
    await this.run(() => this.wallet.load(), false);
  }

  protected signIn(): Promise<void> {
    return this.run(() => this.auth.login());
  }

  protected restore(): Promise<void> {
    return this.run(async () => {
      await this.backups.restore(this.recoveryCode);
      this.recoveryCode = '';
      await this.auth.login();
    });
  }

  private async run(action: () => Promise<void>, thenEnter = true): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await action();
      if (thenEnter) {
        await this.router.navigateByUrl('/admin');
      }
    } catch (e) {
      this.error.set(describe(e));
    } finally {
      this.busy.set(false);
    }
  }
}

function describe(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 403) {
      return 'This wallet is not an admin or moderator.';
    }
    if (e.status === 404) {
      return 'No wallet or backup found for that.';
    }
    return e.error?.detail ?? `The server said ${e.status}.`;
  }
  return e instanceof Error ? e.message : String(e);
}
