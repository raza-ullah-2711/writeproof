import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { WalletService } from '@app/wallet/wallet.service';
import { AdminAdmins } from './admin-admins';
import { AdminTeam, TeamMember } from './admin-team';

const ME = 'M'.repeat(43);
const TEAM: TeamMember[] = [
  {
    publicKey: 'B'.repeat(43),
    role: 'ADMIN',
    source: 'configuration',
    grantedAt: null,
    grantedBy: null,
    hasAccount: true,
    accountId: 'b',
  },
  {
    publicKey: ME,
    role: 'ADMIN',
    source: 'granted',
    grantedAt: '2026-10-01T00:00:00Z',
    grantedBy: 'B'.repeat(43),
    hasAccount: true,
    accountId: 'me',
  },
  {
    publicKey: 'C'.repeat(43),
    role: 'MODERATOR',
    source: 'granted',
    grantedAt: '2026-10-02T00:00:00Z',
    grantedBy: ME,
    hasAccount: true,
    accountId: 'c',
  },
  {
    publicKey: 'D'.repeat(43),
    role: 'MODERATOR',
    source: 'granted',
    grantedAt: '2026-10-02T00:00:00Z',
    grantedBy: ME,
    hasAccount: false,
    accountId: null,
  },
];

describe('AdminAdmins', () => {
  let team: Record<string, ReturnType<typeof vi.fn>>;

  beforeEach(async () => {
    team = {
      members: vi.fn().mockResolvedValue(TEAM),
      grant: vi.fn().mockResolvedValue(undefined),
      revoke: vi.fn().mockResolvedValue(undefined),
    };
    await TestBed.configureTestingModule({
      imports: [AdminAdmins],
      providers: [
        provideRouter([]),
        { provide: AdminTeam, useValue: team },
        { provide: WalletService, useValue: { publicKey: () => ME } },
      ],
    }).compileComponents();
  });

  async function render() {
    const fixture = TestBed.createComponent(AdminAdmins);
    await vi.waitFor(async () => {
      await fixture.whenStable();
      if (!fixture.nativeElement.querySelector('tbody tr')) throw new Error('not yet');
    });
    return fixture;
  }

  const rows = (el: HTMLElement) => [...el.querySelectorAll<HTMLTableRowElement>('tbody tr')];
  const button = (el: Element, label: string) =>
    [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === label)!;

  it('lists the team, and offers no actions on configured admins or yourself', async () => {
    const el: HTMLElement = (await render()).nativeElement;
    const [configured, me, moderator, future] = rows(el);

    expect(configured.textContent).toContain('set in configuration');
    expect(configured.querySelectorAll('.actions button')).toHaveLength(0);
    expect(me.querySelector('.badge.me')?.textContent).toBe('you');
    expect(me.querySelectorAll('.actions button')).toHaveLength(0);
    expect(moderator.querySelector('.badge')?.textContent).toBe('Moderator');
    expect(button(moderator, 'Make admin')).toBeDefined();
    expect(future.textContent).toContain('no account yet');
  });

  it('gives a role by address', async () => {
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;
    const input = el.querySelector<HTMLInputElement>('input[name=address]')!;
    input.value = ' ' + 'E'.repeat(43) + ' ';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    button(el, 'Give role').click();
    await vi.waitFor(() => expect(el.querySelector('.notice')).not.toBeNull());

    expect(team['grant']).toHaveBeenCalledWith(' ' + 'E'.repeat(43) + ' ', 'MODERATOR');
    expect(el.querySelector('.notice')?.textContent).toContain('is now a moderator');
  });

  it('changes and removes roles after confirmation', async () => {
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;

    button(rows(el)[2], 'Make admin').click();
    await fixture.whenStable();
    expect(rows(el)[2].textContent).toContain('Make them an admin?');
    button(rows(el)[2], 'Confirm').click();
    await vi.waitFor(() => expect(team['grant']).toHaveBeenCalledWith('C'.repeat(43), 'ADMIN'));
    await vi.waitFor(() =>
      expect(el.querySelector('.notice')?.textContent).toContain('is now an admin'),
    );

    await fixture.whenStable();
    button(rows(el)[3], 'Remove').click();
    await fixture.whenStable();
    button(rows(el)[3], 'Confirm').click();
    await vi.waitFor(() =>
      expect(el.querySelector('.notice')?.textContent).toContain('no longer has a role'),
    );
    expect(team['revoke']).toHaveBeenCalledWith('D'.repeat(43));
  });

  it("shows the server's reason when a change is refused", async () => {
    team['grant'].mockRejectedValue(
      new HttpErrorResponse({
        status: 409,
        error: { detail: 'Reinstate this account before giving it a role' },
      }),
    );
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;
    const input = el.querySelector<HTMLInputElement>('input[name=address]')!;
    input.value = 'F'.repeat(43);
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    button(el, 'Give role').click();

    await vi.waitFor(() =>
      expect(el.querySelector('[role=alert]')?.textContent).toContain('Reinstate'),
    );
  });
});
