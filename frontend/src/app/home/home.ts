import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { AuthService } from '../auth/auth.service';
import { WalletService } from '../wallet/wallet.service';

@Component({
  selector: 'app-home',
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class Home implements OnInit {
  protected readonly wallet = inject(WalletService);
  protected readonly auth = inject(AuthService);

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

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
    return e.status === 0 ? 'The server is unreachable.' : `Request failed (${e.status}).`;
  }
  return e instanceof Error ? e.message : 'Something went wrong.';
}
