import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AdminDashboard } from './admin-dashboard';
import { AdminService, Dashboard } from './admin.service';

export const DASHBOARD: Dashboard = {
  generatedAt: '2026-10-02T12:00:00Z',
  accounts: {
    total: 200,
    new7d: 12,
    new30d: 40,
    enrolled: 150,
    canReceiveLetters: 180,
    backedUp: 50,
    withContacts: 90,
    calibrationContributors: 7,
  },
  letters: {
    sealed: 1000,
    sealed24h: 20,
    sealed7d: 140,
    replies: 400,
    open: 30,
    open7d: 3,
    last30Days: Array.from({ length: 30 }, (_, i) => ({
      date: `2026-09-${String(i + 1).padStart(2, '0')}`,
      sealed: i % 5,
      open: i % 7 === 0 ? 2 : 0,
    })),
  },
  ledger: {
    size: 1030,
    lastCheckpointSize: 1020,
    lastCheckpointAt: '2026-10-02T11:50:00Z',
    unpublished: 10,
  },
  handwriting: {
    threshold: 0.5,
    checksAccepted: 30,
    checksRejected: 10,
    lettersAccepted: 95,
    lettersRejected: 5,
  },
  rateLimits: { rejections: { 'login-verify': 3, 'handwriting-verify': 9 } },
  system: {
    version: 'dev',
    startedAt: '2026-10-01T00:00:00Z',
    uptimeSeconds: 93_784,
    databaseBytes: 15_400_000,
  },
  moderation: { openReports: 4, reportedLetters: 2, removed: 1 },
};

describe('AdminDashboard', () => {
  let dashboard: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    dashboard = vi.fn().mockResolvedValue(DASHBOARD);
    await TestBed.configureTestingModule({
      imports: [AdminDashboard],
      providers: [provideRouter([]), { provide: AdminService, useValue: { dashboard } }],
    }).compileComponents();
  });

  async function render() {
    const fixture = TestBed.createComponent(AdminDashboard);
    await vi.waitFor(async () => {
      await fixture.whenStable();
      if (!fixture.nativeElement.querySelector('.tile, .error')) throw new Error('not yet');
    });
    return fixture.nativeElement as HTMLElement;
  }

  const tile = (el: HTMLElement, label: string) =>
    [...el.querySelectorAll('.tile')].find(
      (t) => t.querySelector('.label')?.textContent === label,
    )!;

  it('shows accounts, letters, ledger, handwriting, limits and system', async () => {
    const el = await render();

    expect(tile(el, 'Accounts').querySelector('.value')?.textContent).toBe('200');
    expect(tile(el, 'Open reports').classList).toContain('attention');
    expect(tile(el, 'Open reports').querySelector('.sub')?.textContent).toContain('on 2 letters');
    expect(tile(el, 'Accounts').querySelector('.sub')?.textContent).toContain('+12 this week');
    expect(tile(el, 'Handwriting enrolled').querySelector('.sub')?.textContent).toContain(
      '75% of accounts',
    );
    expect(tile(el, 'Sealed letters').querySelector('.value')?.textContent).toBe('1,000');
    expect(tile(el, 'Replies').querySelector('.sub')?.textContent).toContain('40% of sealed');
    expect(tile(el, 'Not yet in a published checkpoint').classList).toContain('attention');
    expect(tile(el, 'Signatures on letters').querySelector('.sub')?.textContent).toContain(
      '5% rejected',
    );
    expect(tile(el, 'Practice checks').querySelector('.sub')?.textContent).toContain(
      '25% rejected',
    );
    expect(tile(el, 'Up for').querySelector('.value')?.textContent).toBe('1d 2h');
    expect(tile(el, 'Up for').querySelector('.sub')?.textContent).toContain(
      'since 2026-10-01 00:00 UTC',
    );
    expect(tile(el, 'Last published checkpoint').querySelector('.sub')?.textContent).toContain(
      '2026-10-02 11:50:00 UTC',
    );
    expect(el.querySelector('.as-of')?.textContent).toBe('As of 2026-10-02 12:00:00 UTC');
    expect(tile(el, 'Database size').querySelector('.value')?.textContent).toBe('14.7 MB');
    const rows = [...el.querySelectorAll('.limits tbody tr')].map((r) => r.textContent?.trim());
    expect(rows[0]).toContain('handwriting-verify');
    expect(el.querySelectorAll('app-letters-chart g')).toHaveLength(30);
  });

  it('refreshes, and reports a failure', async () => {
    const el = await render();
    dashboard.mockRejectedValue(new Error('down'));

    [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Refresh'))!.click();
    await vi.waitFor(() => expect(el.querySelector('[role=alert]')).not.toBeNull());

    expect(dashboard).toHaveBeenCalledTimes(2);
    expect(el.querySelector('[role=alert]')?.textContent).toContain('could not be loaded');
  });

  it('shows dashes rather than dividing by zero', async () => {
    dashboard.mockResolvedValue({
      ...DASHBOARD,
      accounts: { ...DASHBOARD.accounts, total: 0, enrolled: 0 },
      handwriting: { ...DASHBOARD.handwriting, lettersAccepted: 0, lettersRejected: 0 },
      rateLimits: { rejections: {} },
    });
    const el = await render();

    expect(tile(el, 'Handwriting enrolled').querySelector('.sub')?.textContent).toContain('–%');
    expect(tile(el, 'Signatures on letters').querySelector('.sub')?.textContent).toContain(
      '– rejected',
    );
    expect(el.textContent).toContain('No requests have been rate-limited');
  });
});
