import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AdminService } from './admin.service';

/** The admin area's frame: its own navigation, shown only to admins and moderators. */
@Component({
  selector: 'app-admin-shell',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  template: `
    <section class="admin">
      <header>
        <h2>Admin</h2>
        <span class="role">{{ admin.role() === 'ADMIN' ? 'Administrator' : 'Moderator' }}</span>
      </header>
      <p class="limits">
        Admins see counts and metadata only. Sealed letters, contacts and handwriting are encrypted
        and can't be read here, and nothing can rewrite a letter or the ledger. Every admin action
        is recorded permanently.
      </p>
      <nav class="admin-nav">
        @if (admin.role() === 'ADMIN') {
          <a
            routerLink="/admin"
            routerLinkActive="active"
            [routerLinkActiveOptions]="{ exact: true }"
            >Dashboard</a
          >
          <a routerLink="/admin/accounts" routerLinkActive="active">Accounts</a>
        }
        <a routerLink="/admin/moderation" routerLinkActive="active">Moderation</a>
        @if (admin.role() === 'ADMIN') {
          <a routerLink="/admin/system" routerLinkActive="active">System</a>
          <a routerLink="/admin/admins" routerLinkActive="active">Admins</a>
          <a routerLink="/admin/preserved" routerLinkActive="active">Preserved</a>
          <a routerLink="/admin/audit" routerLinkActive="active">Audit log</a>
        }
      </nav>
      <router-outlet />
    </section>
  `,
  styles: `
    .admin {
      font-family: system-ui, sans-serif;
    }
    header {
      display: flex;
      align-items: baseline;
      gap: 0.75rem;
    }
    .role {
      font-size: 0.75rem;
      padding: 0.1rem 0.5rem;
      border-radius: 999px;
      background: var(--info-bg);
      color: var(--info-text);
    }
    .limits,
    .empty {
      font-size: 0.8125rem;
      color: var(--text-secondary);
    }
    .admin-nav {
      display: flex;
      gap: 1rem;
      border-bottom: 1px solid var(--border);
      padding-bottom: 0.5rem;
      margin-bottom: 1rem;
    }
    .admin-nav a {
      color: var(--text);
    }
    .admin-nav a.active {
      font-weight: 600;
    }
  `,
})
export class AdminShell {
  protected readonly admin = inject(AdminService);
}
