import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { AdminModeration } from './admin-moderation';
import { adminOnlyGuard } from './admin-only.guard';
import { AdminRole, AdminService } from './admin.service';
import { Moderation, ModerationCase } from './moderation';

const CASE: ModerationCase = {
  letterHash: 'h1',
  author: 'A'.repeat(43),
  sentAt: '2026-10-02T12:00:00.000Z',
  body: 'Buy followers now',
  ledgerSeq: 12,
  openReportsByCategory: { spam: 3, other: 1 },
  openReports: 4,
  firstReportedAt: '2026-10-02T13:00:00Z',
  reports: [
    {
      id: 1,
      category: 'spam',
      note: 'Posted everywhere',
      createdAt: '2026-10-02T13:00:00Z',
      fromAccount: true,
      resolution: null,
    },
    {
      id: 2,
      category: 'other',
      note: null,
      createdAt: '2026-10-02T13:05:00Z',
      fromAccount: false,
      resolution: null,
    },
  ],
  removal: null,
  hold: null,
};

describe('AdminModeration', () => {
  const role = signal<AdminRole | null>('MODERATOR');
  let service: Record<string, ReturnType<typeof vi.fn>>;

  beforeEach(async () => {
    role.set('MODERATOR');
    service = {
      queue: vi.fn().mockResolvedValue([CASE]),
      letter: vi.fn().mockResolvedValue({
        ...CASE,
        letterHash: 'h9',
        openReports: 0,
        openReportsByCategory: {},
        reports: [],
      }),
      dismiss: vi.fn().mockResolvedValue(undefined),
      remove: vi.fn().mockResolvedValue({ ...CASE, body: null }),
      appeals: vi.fn().mockResolvedValue([]),
      restore: vi.fn().mockResolvedValue(CASE),
      uphold: vi.fn().mockResolvedValue({ ...CASE, body: null }),
    };
    await TestBed.configureTestingModule({
      imports: [AdminModeration],
      providers: [
        provideRouter([]),
        { provide: Moderation, useValue: service },
        { provide: AdminService, useValue: { role } },
      ],
    }).compileComponents();
  });

  async function render() {
    const fixture = TestBed.createComponent(AdminModeration);
    await vi.waitFor(async () => {
      await fixture.whenStable();
      if (!fixture.nativeElement.querySelector('.case, .hint + h3 + .hint'))
        throw new Error('not yet');
    });
    return fixture;
  }

  const button = (el: HTMLElement, label: string) =>
    [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === label)!;

  it('shows reported letters with their text, counts and reports', async () => {
    const el: HTMLElement = (await render()).nativeElement;

    expect(el.querySelector('.case .body')?.textContent).toBe('Buy followers now');
    expect(el.querySelector('.case .counts')?.textContent).toContain('4 open reports');
    expect(el.querySelector('.case .counts')?.textContent).toContain('Spam × 3');
    expect(el.querySelectorAll('.case .reports li')).toHaveLength(2);
    expect(el.querySelector('.case .reports')?.textContent).toContain('Posted everywhere');
    expect(el.querySelector('.case a')?.getAttribute('href')).toBe('/open/h1');
  });

  it('removes with the most reported category by default, after confirmation', async () => {
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;

    button(el, 'Remove').click();
    await fixture.whenStable();
    expect(el.querySelector<HTMLSelectElement>('select[name=category]')!.value).toBe('spam');
    button(el, 'Remove').click();
    await vi.waitFor(() => expect(el.querySelector('.notice')).not.toBeNull());

    expect(service['remove']).toHaveBeenCalledWith('h1', 'spam', '');
    expect(el.querySelector('.notice')?.textContent).toContain('Letter hidden');
    expect(service['queue']).toHaveBeenCalledTimes(2);
  });

  it('dismisses with a note', async () => {
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;

    button(el, 'Dismiss').click();
    await fixture.whenStable();
    const note = el.querySelector<HTMLInputElement>('input[name=note]')!;
    note.value = 'Fair criticism';
    note.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    button(el, 'Dismiss reports').click();
    await vi.waitFor(() => expect(el.querySelector('.notice')).not.toBeNull());

    expect(service['dismiss']).toHaveBeenCalledWith('h1', 'Fair criticism');
  });

  it('looks up any letter by its link, and explains an unknown one', async () => {
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;
    const input = el.querySelector<HTMLInputElement>('input[name=lookup]')!;
    input.value = 'https://writeproof.example/open/h9?x=1';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    button(el, 'Look up').click();
    await vi.waitFor(() => expect(el.querySelectorAll('.case')).toHaveLength(2));
    expect(service['letter']).toHaveBeenCalledWith('h9');
    expect(button(el, 'Dismiss')).toBeDefined(); // the queued case still offers it

    service['letter'].mockRejectedValue(new HttpErrorResponse({ status: 404 }));
    button(el, 'Look up').click();
    await vi.waitFor(() => expect(el.querySelector('[role=alert]')).not.toBeNull());
    expect(el.querySelector('[role=alert]')?.textContent).toContain('No open letter matches');
  });

  it('sends moderators from admin-only pages to moderation', () => {
    const router = TestBed.inject(Router);
    const run = () => TestBed.runInInjectionContext(() => adminOnlyGuard({} as never, {} as never));

    expect(router.serializeUrl(run() as never)).toBe('/admin/moderation');
    role.set('ADMIN');
    expect(run()).toBe(true);
  });

  it('lists appeals first, and restores a letter after confirmation', async () => {
    const appealed = {
      ...CASE,
      letterHash: 'h2',
      openReports: 0,
      openReportsByCategory: {},
      reports: [],
      hold: {
        category: 'spam',
        heldAt: '2026-10-01T00:00:00Z',
        deleteAfter: '2026-10-15T00:00:00Z',
        appeal: 'It was a joke between friends',
        appealedAt: '2026-10-02T00:00:00Z',
      },
    };
    service['appeals'].mockResolvedValue([appealed]);
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;

    expect(el.textContent).toContain('Appeals (1)');
    expect(el.textContent).toContain('It was a joke between friends');
    button(el, 'Restore').click();
    await fixture.whenStable();
    button(el, 'Restore the letter').click();
    await vi.waitFor(() => expect(el.querySelector('.notice')).not.toBeNull());

    expect(service['restore']).toHaveBeenCalledWith('h2', '');
    expect(el.querySelector('.notice')?.textContent).toContain('public again');
  });

  it('warns before a child-safety removal', async () => {
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;

    button(el, 'Remove').click();
    await fixture.whenStable();
    const select = el.querySelector<HTMLSelectElement>('select[name=category]')!;
    select.value = 'child_safety';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await fixture.whenStable();

    expect(el.querySelector('.warn')?.textContent).toContain('NCMEC');
    expect(button(el, 'Remove and preserve')).toBeTruthy();
  });
});
