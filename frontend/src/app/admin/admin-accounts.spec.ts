import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AccountAdmin, AccountSummary } from './account-admin';
import { AdminAccount } from './admin-account';
import { AdminAccounts } from './admin-accounts';
import { ActivatedRoute, convertToParamMap } from '@angular/router';

const ACCOUNT: AccountSummary = {
  accountId: 'acc-1',
  publicKey: 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq',
  createdAt: '2026-09-01T10:00:00Z',
  enrolled: true,
  canReceiveLetters: true,
  backedUp: false,
  lettersSent: 3,
  lettersReceived: 5,
  openLetters: 1,
  suspension: null,
  role: null,
};

describe('Admin accounts', () => {
  let service: Record<string, ReturnType<typeof vi.fn>>;

  beforeEach(async () => {
    service = {
      search: vi
        .fn()
        .mockResolvedValue([
          ACCOUNT,
          { ...ACCOUNT, accountId: 'acc-2', suspension: { since: '', reason: 'x' }, role: null },
        ]),
      detail: vi.fn().mockResolvedValue({
        account: ACCOUNT,
        lastLetterAt: '2026-10-01T09:00:00Z',
        usesContacts: true,
        calibrationContributor: false,
        sessionsRevokedAt: null,
        history: [],
      }),
      suspend: vi.fn().mockResolvedValue(undefined),
      reinstate: vi.fn().mockResolvedValue(undefined),
      signOut: vi.fn().mockResolvedValue({ tokensBefore: '' }),
      resetRateLimits: vi.fn().mockResolvedValue({ buckets: 2 }),
    };
    await TestBed.configureTestingModule({
      imports: [AdminAccounts, AdminAccount],
      providers: [
        provideRouter([]),
        { provide: AccountAdmin, useValue: service },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: 'acc-1' }) } },
        },
      ],
    }).compileComponents();
  });

  const button = (el: HTMLElement, label: string) =>
    [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === label)!;

  async function settle(
    fixture: { whenStable(): Promise<unknown>; nativeElement: HTMLElement },
    sel: string,
  ) {
    await vi.waitFor(async () => {
      await fixture.whenStable();
      if (!fixture.nativeElement.querySelector(sel)) throw new Error('not yet');
    });
  }

  it('lists the newest accounts and searches by address prefix', async () => {
    const fixture = TestBed.createComponent(AdminAccounts);
    await settle(fixture, 'tbody tr');
    const el: HTMLElement = fixture.nativeElement;

    expect(service['search']).toHaveBeenCalledWith('');
    expect(el.querySelectorAll('tbody tr')).toHaveLength(2);
    expect(el.querySelector('tbody tr a')?.getAttribute('href')).toBe('/admin/accounts/acc-1');
    expect(el.querySelectorAll('tbody tr')[1].textContent).toContain('Suspended');

    const input = el.querySelector<HTMLInputElement>('input[name=query]')!;
    input.value = 'ABCD';
    input.dispatchEvent(new Event('input'));
    button(el, 'Search').click();
    await vi.waitFor(() => expect(service['search']).toHaveBeenLastCalledWith('ABCD'));
  });

  it('explains a too-short search', async () => {
    service['search'].mockRejectedValue(new HttpErrorResponse({ status: 400 }));
    const fixture = TestBed.createComponent(AdminAccounts);
    await settle(fixture, '[role=alert]');

    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain('at least 4');
  });

  it('suspends with a required reason, after confirmation', async () => {
    const fixture = TestBed.createComponent(AdminAccount);
    await settle(fixture, '.facts');
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.facts')?.textContent).toContain('3 sent, 5 received, 1 open');
    expect(el.textContent).toContain('encrypted and not shown here');

    button(el, 'Suspend').click();
    await fixture.whenStable();
    expect(button(el, 'Confirm').disabled).toBe(true);
    const reason = el.querySelector<HTMLTextAreaElement>('textarea[name=reason]')!;
    reason.value = 'Repeated spam reports';
    reason.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    button(el, 'Confirm').click();
    await settle(fixture, '.notice');

    expect(service['suspend']).toHaveBeenCalledWith('acc-1', 'Repeated spam reports');
    expect(el.querySelector('.notice')?.textContent).toContain('Suspended');
    expect(service['detail']).toHaveBeenCalledTimes(2);
    // The notice appears only once the refreshed account is shown.
    expect(service['detail'].mock.invocationCallOrder[1]).toBeGreaterThan(
      service['suspend'].mock.invocationCallOrder[0],
    );
  });

  it('runs the other actions and can cancel', async () => {
    const fixture = TestBed.createComponent(AdminAccount);
    await settle(fixture, '.facts');
    const el: HTMLElement = fixture.nativeElement;

    button(el, 'Sign out everywhere').click();
    await fixture.whenStable();
    button(el, 'Cancel').click();
    await fixture.whenStable();
    expect(service['signOut']).not.toHaveBeenCalled();

    button(el, 'Clear rate limits').click();
    await fixture.whenStable();
    button(el, 'Confirm').click();
    await settle(fixture, '.notice');
    expect(el.querySelector('.notice')?.textContent).toContain('2 active limits');
  });

  it('shows the server reason when an action is refused, and blocks suspending admins', async () => {
    service['detail'].mockResolvedValue({
      account: { ...ACCOUNT, role: 'ADMIN' },
      lastLetterAt: null,
      usesContacts: false,
      calibrationContributor: false,
      sessionsRevokedAt: null,
      history: [
        {
          id: 1,
          at: '2026-10-01T00:00:00Z',
          actor: 'K'.repeat(43),
          actorRole: 'ADMIN',
          action: 'account.signed-out',
          target: ACCOUNT.publicKey,
          detail: {},
        },
      ],
    });
    service['signOut'].mockRejectedValue(
      new HttpErrorResponse({ status: 409, error: { detail: 'Nope' } }),
    );
    const fixture = TestBed.createComponent(AdminAccount);
    await settle(fixture, '.facts');
    const el: HTMLElement = fixture.nativeElement;

    expect(button(el, 'Suspend').disabled).toBe(true);
    expect(el.querySelector('.history')?.textContent).toContain('account.signed-out');
    button(el, 'Sign out everywhere').click();
    await fixture.whenStable();
    button(el, 'Confirm').click();
    await settle(fixture, '[role=alert]');
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Nope');
  });
});
