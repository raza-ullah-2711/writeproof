import { TestBed } from '@angular/core/testing';
import { SystemStatusService } from '@app/system/system-status.service';
import { AdminSystem } from './admin-system';
import { SystemAdmin, SystemOverview } from './system-admin';

const OVERVIEW: SystemOverview = {
  settings: {
    registrationOpen: true,
    sendingEnabled: true,
    openLettersEnabled: false,
    announcement: 'Hi',
  },
  changes: [
    { key: 'open_letters_enabled', updatedAt: '2026-10-03T08:00:00Z', updatedBy: 'K'.repeat(43) },
  ],
  rateLimitsEnabled: true,
  rateLimitScale: 1,
  rateLimits: [
    {
      name: 'send-letter',
      method: 'POST',
      path: '/api/letters',
      capacity: 30,
      windowSeconds: 3600,
      perAccount: true,
    },
    {
      name: 'login-verify',
      method: 'POST',
      path: '/api/auth/verify',
      capacity: 30,
      windowSeconds: 600,
      perAccount: false,
    },
  ],
  handwritingThreshold: 0.5,
  scoresExposed: false,
  calibrationContributors: 3,
  genuineSamples: 40,
  forgerySamples: 12,
  ledgerSize: 99,
  lastCheckpointSize: 90,
  lastCheckpointAt: '2026-10-03T07:50:00Z',
};

describe('AdminSystem', () => {
  let service: Record<string, ReturnType<typeof vi.fn>>;
  let refresh: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    refresh = vi.fn().mockResolvedValue(undefined);
    service = {
      overview: vi.fn().mockResolvedValue(OVERVIEW),
      change: vi.fn().mockResolvedValue(OVERVIEW.settings),
      publishCheckpoint: vi.fn().mockResolvedValue({ published: true, size: 99 }),
      auditLedger: vi.fn().mockResolvedValue({
        chainIntact: true,
        length: 99,
        brokenAt: null,
        checkpointsChecked: 5,
        rootMismatches: [],
        badSignatures: [],
        ok: true,
      }),
    };
    await TestBed.configureTestingModule({
      imports: [AdminSystem],
      providers: [
        { provide: SystemAdmin, useValue: service },
        { provide: SystemStatusService, useValue: { refresh } },
      ],
    }).compileComponents();
  });

  async function render() {
    const fixture = TestBed.createComponent(AdminSystem);
    await vi.waitFor(async () => {
      await fixture.whenStable();
      if (!fixture.nativeElement.querySelector('.flags li')) throw new Error('not yet');
    });
    return fixture;
  }

  const button = (el: Element, label: string) =>
    [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === label)!;

  it('shows each switch, configuration and rate limits', async () => {
    const el: HTMLElement = (await render()).nativeElement;
    const rows = [...el.querySelectorAll('.flags li')];

    expect(rows.map((r) => r.querySelector('.state')?.textContent)).toEqual(['On', 'On', 'Paused']);
    expect(rows[2].textContent).toContain('since 2026-10-03 08:00 UTC');
    expect(el.querySelector<HTMLInputElement>('input[name=announcement]')!.value).toBe('Hi');
    expect(el.textContent).toContain('3 contributors, 40 genuine and 12 forgery samples');
    expect(
      [...el.querySelectorAll('tbody tr')].map((r) => r.textContent?.replace(/\s+/g, ' ')),
    ).toEqual([expect.stringContaining('30 / 1 h'), expect.stringContaining('30 / 10 min')]);
  });

  it('pauses a feature only after confirmation, then refreshes the public status', async () => {
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;
    const registration = el.querySelectorAll('.flags li')[0];

    button(registration, 'Pause').click();
    await fixture.whenStable();
    expect(registration.textContent).toContain('Nobody can create an account');
    expect(service['change']).not.toHaveBeenCalled();
    button(registration, 'Confirm').click();
    await vi.waitFor(() => expect(el.querySelector('.notice')).not.toBeNull());

    expect(service['change']).toHaveBeenCalledWith({ registrationOpen: false });
    expect(el.querySelector('.notice')?.textContent).toContain('New accounts: paused');
    expect(refresh).toHaveBeenCalled();
  });

  it('saves the announcement and runs the ledger tools', async () => {
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;
    const input = el.querySelector<HTMLInputElement>('input[name=announcement]')!;
    input.value = 'Maintenance at 22:00 UTC';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    button(el, 'Save').click();
    await vi.waitFor(() =>
      expect(service['change']).toHaveBeenCalledWith({ announcement: 'Maintenance at 22:00 UTC' }),
    );

    button(el, 'Publish a checkpoint now').click();
    await vi.waitFor(() =>
      expect(el.querySelector('.notice')?.textContent).toContain('Published a checkpoint of 99'),
    );
    button(el, 'Run a ledger audit').click();
    await vi.waitFor(() =>
      expect(el.querySelector('.audit')?.textContent).toContain('✓ Ledger verified'),
    );
  });

  it('says so when the ledger is empty', async () => {
    service['publishCheckpoint'].mockResolvedValue({ published: false, size: 0 });
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;

    button(el, 'Publish a checkpoint now').click();
    await vi.waitFor(() =>
      expect(el.querySelector('.notice')?.textContent).toContain('The ledger is empty'),
    );
  });

  it('explains a failed audit', async () => {
    service['auditLedger'].mockResolvedValue({
      chainIntact: false,
      length: 99,
      brokenAt: 41,
      checkpointsChecked: 5,
      rootMismatches: [60, 90],
      badSignatures: [],
      ok: false,
    });
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;

    button(el, 'Run a ledger audit').click();
    await vi.waitFor(() => expect(el.querySelector('.audit.bad')).not.toBeNull());
    expect(el.querySelector('.audit')?.textContent).toContain('breaks at entry #41');
    expect(el.querySelector('.audit')?.textContent).toContain('60, 90');
  });
});
