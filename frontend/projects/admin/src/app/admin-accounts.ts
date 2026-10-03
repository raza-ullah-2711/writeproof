import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AccountAdmin, AccountSummary } from './account-admin';

/** Find accounts by address; newest first when the search is empty. */
@Component({
  selector: 'app-admin-accounts',
  imports: [DatePipe, FormsModule, RouterLink],
  template: `
    <form class="search" (ngSubmit)="search()" role="search">
      <label>
        Find an account by address
        <input
          name="query"
          [(ngModel)]="query"
          placeholder="First characters of the address (at least 4)"
          autocomplete="off"
          spellcheck="false"
        />
      </label>
      <button type="submit" [disabled]="busy()">Search</button>
    </form>
    @if (error(); as message) {
      <p class="error" role="alert">{{ message }}</p>
    }
    @if (results(); as list) {
      <p class="hint">
        {{ query.trim() ? list.length + ' matching' : 'Newest ' + list.length }}
        {{ list.length === 1 ? 'account' : 'accounts' }}
      </p>
      <table>
        <thead>
          <tr>
            <th>Address</th>
            <th>Joined</th>
            <th>Handwriting</th>
            <th>Letters sent / received</th>
            <th>Open</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          @for (a of list; track a.accountId) {
            <tr>
              <td>
                <a [routerLink]="['/admin/accounts', a.accountId]"
                  ><code>{{ short(a.publicKey) }}</code></a
                >
              </td>
              <td>{{ a.createdAt | date: 'yyyy-MM-dd' : 'UTC' }}</td>
              <td>{{ a.enrolled ? 'Enrolled' : '–' }}</td>
              <td>{{ a.lettersSent }} / {{ a.lettersReceived }}</td>
              <td>{{ a.openLetters }}</td>
              <td>
                @if (a.suspension) {
                  <span class="badge suspended">Suspended</span>
                }
                @if (a.role) {
                  <span class="badge role">{{ a.role === 'ADMIN' ? 'Admin' : 'Moderator' }}</span>
                }
                @if (!a.suspension && !a.role) {
                  Active
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    } @else if (!error()) {
      <p>Loading&hellip;</p>
    }
  `,
  styleUrl: './admin-accounts.scss',
})
export class AdminAccounts implements OnInit {
  private readonly accounts = inject(AccountAdmin);

  protected readonly results = signal<AccountSummary[] | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected query = '';

  ngOnInit(): Promise<void> {
    return this.search();
  }

  protected async search(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      this.results.set(await this.accounts.search(this.query));
    } catch (e) {
      this.results.set(null);
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 400
          ? 'Search with at least 4 characters of an address.'
          : 'Accounts could not be loaded.',
      );
    } finally {
      this.busy.set(false);
    }
  }

  protected short(key: string): string {
    return `${key.slice(0, 10)}…${key.slice(-4)}`;
  }
}
