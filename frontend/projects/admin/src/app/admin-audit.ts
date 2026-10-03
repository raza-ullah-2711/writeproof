import { JsonPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { AdminService, AuditEntry } from './admin.service';

/** The permanent record of admin actions, newest first. */
@Component({
  selector: 'app-admin-audit',
  imports: [JsonPipe],
  template: `
    <p class="hint">
      Every admin action is recorded here and can't be edited or deleted, not even by an admin.
    </p>
    @if (error(); as message) {
      <p class="error" role="alert">{{ message }}</p>
    }
    @if (entries(); as list) {
      @if (list.length === 0) {
        <p class="hint">No admin actions yet.</p>
      } @else {
        <table>
          <thead>
            <tr>
              <th>When</th>
              <th>Who</th>
              <th>Action</th>
              <th>Target</th>
              <th>Details</th>
            </tr>
          </thead>
          <tbody>
            @for (e of list; track e.id) {
              <tr>
                <td>{{ e.at }}</td>
                <td>
                  <code [title]="e.actor">{{ short(e.actor) }}</code>
                  <small>{{ e.actorRole }}</small>
                </td>
                <td>{{ e.action }}</td>
                <td>{{ e.target ?? '–' }}</td>
                <td>
                  <code class="detail">{{ e.detail | json }}</code>
                </td>
              </tr>
            }
          </tbody>
        </table>
        @if (more()) {
          <button type="button" (click)="loadMore()">Older entries</button>
        }
      }
    } @else if (!error()) {
      <p>Loading&hellip;</p>
    }
  `,
  styles: `
    .hint {
      font-size: 0.8125rem;
      color: #555;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      font-size: 0.8125rem;
    }
    th,
    td {
      text-align: left;
      vertical-align: top;
      padding: 0.3rem 0.5rem 0.3rem 0;
      border-bottom: 1px solid #eee;
    }
    .detail {
      white-space: pre-wrap;
      overflow-wrap: anywhere;
      font-size: 0.75rem;
    }
    .error {
      color: #b00020;
    }
  `,
})
export class AdminAudit implements OnInit {
  private readonly admin = inject(AdminService);

  protected readonly entries = signal<AuditEntry[] | null>(null);
  protected readonly more = signal(false);
  protected readonly error = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    await this.fetch();
  }

  protected loadMore(): Promise<void> {
    return this.fetch(this.entries()?.at(-1)?.id);
  }

  protected short(key: string): string {
    return `${key.slice(0, 8)}…${key.slice(-4)}`;
  }

  private async fetch(before?: number): Promise<void> {
    try {
      const page = await this.admin.audit(before);
      this.entries.update((list) => [...(before === undefined ? [] : (list ?? [])), ...page]);
      this.more.set(page.length === 50);
    } catch {
      this.error.set('The audit log could not be loaded.');
    }
  }
}
