import { DatePipe, JsonPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AccountAdmin, AccountDetail } from './account-admin';

type Pending = 'suspend' | 'reinstate' | 'sign-out' | 'reset' | null;

/** One account: what the server knows about it, its admin history, and the actions on it. */
@Component({
  selector: 'app-admin-account',
  imports: [DatePipe, FormsModule, JsonPipe, RouterLink],
  templateUrl: './admin-account.html',
  styleUrl: './admin-account.scss',
})
export class AdminAccount implements OnInit {
  private readonly accounts = inject(AccountAdmin);
  private readonly route = inject(ActivatedRoute);

  protected readonly detail = signal<AccountDetail | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);
  /** The action awaiting confirmation. */
  protected readonly pending = signal<Pending>(null);
  protected reason = '';

  private get id(): string {
    return this.route.snapshot.paramMap.get('id') ?? '';
  }

  async ngOnInit(): Promise<void> {
    try {
      this.detail.set(await this.accounts.detail(this.id));
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 404
          ? 'There is no such account.'
          : 'The account could not be loaded.',
      );
    }
  }

  protected ask(action: Pending): void {
    this.pending.set(action);
    this.reason = '';
    this.notice.set(null);
    this.error.set(null);
  }

  protected confirm(): Promise<void> {
    const action = this.pending();
    return this.run(async () => {
      let message = '';
      switch (action) {
        case 'suspend':
          await this.accounts.suspend(this.id, this.reason.trim());
          message = 'Suspended. They can still sign in and read, but not send.';
          break;
        case 'reinstate':
          await this.accounts.reinstate(this.id);
          message = 'Reinstated. They can send again.';
          break;
        case 'sign-out':
          await this.accounts.signOut(this.id);
          message = 'Signed out everywhere. They can sign in again with their wallet.';
          break;
        case 'reset': {
          const { buckets } = await this.accounts.resetRateLimits(this.id);
          message = `Rate limits cleared (${buckets} active ${buckets === 1 ? 'limit' : 'limits'}).`;
          break;
        }
      }
      this.pending.set(null);
      // Show the new state and history first, so the notice never sits next to stale details.
      this.detail.set(await this.accounts.detail(this.id));
      this.notice.set(message);
    });
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
