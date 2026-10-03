import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { WalletService } from '../wallet/wallet.service';
import { AdminTeam, TeamMember } from './admin-team';
import { AdminRole } from './admin.service';

type Pending = { member: TeamMember; action: 'change' | 'revoke'; role?: AdminRole } | null;

/** Who administers Writeproof: grant, change and remove roles by wallet address. */
@Component({
  selector: 'app-admin-admins',
  imports: [DatePipe, FormsModule, RouterLink],
  templateUrl: './admin-admins.html',
  styleUrl: './admin-admins.scss',
})
export class AdminAdmins implements OnInit {
  private readonly team = inject(AdminTeam);
  protected readonly wallet = inject(WalletService);

  protected readonly members = signal<TeamMember[] | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly pending = signal<Pending>(null);
  protected address = '';
  protected role: AdminRole = 'MODERATOR';

  ngOnInit(): Promise<void> {
    return this.load();
  }

  protected label(role: AdminRole): string {
    return role === 'ADMIN' ? 'Admin' : 'Moderator';
  }

  /** "an admin", "a moderator". */
  protected withArticle(role: AdminRole): string {
    return role === 'ADMIN' ? 'an admin' : 'a moderator';
  }

  protected isMe(m: TeamMember): boolean {
    return m.publicKey === this.wallet.publicKey();
  }

  protected short(key: string): string {
    return `${key.slice(0, 10)}…${key.slice(-4)}`;
  }

  protected add(): Promise<void> {
    return this.run(async () => {
      await this.team.grant(this.address, this.role);
      const who = this.short(this.address.trim());
      this.address = '';
      await this.load();
      this.notice.set(`${who} is now ${this.withArticle(this.role)}.`);
    });
  }

  protected ask(member: TeamMember, action: 'change' | 'revoke'): void {
    this.pending.set({
      member,
      action,
      role: action === 'change' ? (member.role === 'ADMIN' ? 'MODERATOR' : 'ADMIN') : undefined,
    });
    this.notice.set(null);
  }

  protected confirm(): Promise<void> {
    const p = this.pending();
    if (!p) {
      return Promise.resolve();
    }
    return this.run(async () => {
      if (p.action === 'change' && p.role) {
        await this.team.grant(p.member.publicKey, p.role);
      } else {
        await this.team.revoke(p.member.publicKey);
      }
      this.pending.set(null);
      await this.load();
      this.notice.set(
        p.action === 'change'
          ? `${this.short(p.member.publicKey)} is now ${this.withArticle(p.role!)}.`
          : `${this.short(p.member.publicKey)} no longer has a role.`,
      );
    });
  }

  private async load(): Promise<void> {
    try {
      this.members.set(await this.team.members());
    } catch {
      this.error.set('The admin team could not be loaded.');
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
