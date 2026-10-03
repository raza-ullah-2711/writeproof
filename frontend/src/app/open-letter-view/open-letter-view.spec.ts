import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { ContactsService } from '../contacts/contacts.service';
import { OpenLetter, OpenLettersService } from '../open-letters/open-letters.service';
import { OpenLetterView } from './open-letter-view';

const AUTHOR = 'Q'.repeat(43);
const LETTER: OpenLetter = {
  letterHash: 'hash-1',
  author: AUTHOR,
  sentAt: '2026-10-02T12:00:00.000Z',
  body: 'To everyone:\n<b>not markup</b>',
  signature: 's',
  handwritingHash: 'hw',
  handwritingScore: 0.874,
  ledger: { seq: 9, prevHash: '', payloadHash: 'hash-1', recordedAtMillis: 0, entryHash: 'e' },
  removed: null,
};

/** The radio whose label reads `label` (Angular keeps bound radio values off the DOM). */
const radio = (el: HTMLElement, label: string) =>
  [...el.querySelectorAll('fieldset label')]
    .find((l) => l.textContent?.trim() === label)!
    .querySelector<HTMLInputElement>('input')!;

describe('OpenLetterView', () => {
  const authenticated = signal(false);
  let service: {
    get: ReturnType<typeof vi.fn>;
    verify: ReturnType<typeof vi.fn>;
    report: ReturnType<typeof vi.fn>;
  };
  let petnames: Record<string, string>;

  beforeEach(async () => {
    authenticated.set(false);
    petnames = {};
    service = {
      get: vi.fn().mockResolvedValue(LETTER),
      verify: vi.fn().mockResolvedValue({
        signatureValid: true,
        ledgerValid: true,
        ledgerProblem: null,
        ledgerCheckpointSize: 30,
      }),
      report: vi.fn().mockResolvedValue(undefined),
    };
    await TestBed.configureTestingModule({
      imports: [OpenLetterView],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { authenticated } },
        { provide: OpenLettersService, useValue: service },
        {
          provide: ContactsService,
          useValue: {
            ensureLoaded: vi.fn().mockResolvedValue(undefined),
            petname: (a: string) => petnames[a] ?? null,
          },
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ hash: 'hash-1' }) } },
        },
      ],
    }).compileComponents();
  });

  async function render() {
    const fixture = TestBed.createComponent(OpenLetterView);
    await vi.waitFor(async () => {
      await fixture.whenStable();
      if (!fixture.nativeElement.querySelector('.checks, .error')) throw new Error('not yet');
    });
    return fixture;
  }

  it('shows and verifies the letter without an account, as plain text', async () => {
    const el: HTMLElement = (await render()).nativeElement;

    expect(service.verify).toHaveBeenCalledWith(LETTER, 'hash-1');
    expect(el.querySelector('.body')?.textContent).toBe('To everyone:\n<b>not markup</b>');
    expect(el.querySelector('.body b')).toBeNull();
    expect(el.querySelector('.byline')?.textContent).toContain('QQQQQQQQ…QQQQ');
    expect(el.querySelector('.byline .add-contact')).toBeNull();
    const checks = [...el.querySelectorAll('.checks li')].map((li) => li.textContent?.trim());
    expect(checks[0]).toContain("✓ Signed by the author's wallet");
    expect(checks[1]).toMatch(/✓ Signed by hand \(similarity 0\.87/);
    expect(checks[2]).toMatch(
      /✓ In the ledger as entry #9\s+\(proven against a signed checkpoint of 30 entries\)/,
    );
  });

  it('shows your name for the author when signed in, or offers to add them', async () => {
    authenticated.set(true);
    let el: HTMLElement = (await render()).nativeElement;
    expect(el.querySelector('.byline .add-contact')?.getAttribute('href')).toBe(
      `/contacts?add=${AUTHOR}`,
    );

    petnames[AUTHOR] = 'Aunt Rosa';
    el = (await render()).nativeElement;
    expect(el.querySelector('.byline strong')?.textContent).toBe('Aunt Rosa');
  });

  it('shows failed checks', async () => {
    service.verify.mockResolvedValue({
      signatureValid: false,
      ledgerValid: false,
      ledgerProblem: 'The ledger key changed',
      ledgerCheckpointSize: null,
    });
    const el: HTMLElement = (await render()).nativeElement;

    const checks = [...el.querySelectorAll('.checks li')].map((li) => li.textContent?.trim());
    expect(checks[0]).toContain('✗');
    expect(checks[2]).toContain('The ledger key changed');
  });

  it('says when there is no letter at the link', async () => {
    service.get.mockRejectedValue(new HttpErrorResponse({ status: 404 }));
    const el: HTMLElement = (await render()).nativeElement;

    expect(el.querySelector('[role=alert]')?.textContent).toContain('no open letter at this link');
  });

  it('lets any reader report a letter, once', async () => {
    const fixture = TestBed.createComponent(OpenLetterView);
    await vi.waitFor(async () => {
      await fixture.whenStable();
      if (!fixture.nativeElement.querySelector('.checks')) throw new Error('not yet');
    });
    const el: HTMLElement = fixture.nativeElement;
    const button = (label: string) =>
      [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === label)!;

    button('Report this letter').click();
    await fixture.whenStable();
    expect(button('Send report').disabled).toBe(true);
    radio(el, 'Harassment').click();
    const note = el.querySelector<HTMLTextAreaElement>('textarea[name=note]')!;
    note.value = 'Targets a named person';
    note.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    button('Send report').click();
    await vi.waitFor(() => expect(el.querySelector('.report .notice')).not.toBeNull());

    expect(service.report).toHaveBeenCalledWith('hash-1', 'harassment', 'Targets a named person');
    expect(el.querySelector('.report .notice')?.textContent).toContain('A moderator will review');
  });

  it('says when the reader already reported it', async () => {
    service.report.mockRejectedValue(new HttpErrorResponse({ status: 409 }));
    const fixture = TestBed.createComponent(OpenLetterView);
    await vi.waitFor(async () => {
      await fixture.whenStable();
      if (!fixture.nativeElement.querySelector('.checks')) throw new Error('not yet');
    });
    const el: HTMLElement = fixture.nativeElement;
    [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Report'))!.click();
    await fixture.whenStable();
    radio(el, 'Spam').click();
    await fixture.whenStable();
    [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Send report'))!.click();

    await vi.waitFor(() =>
      expect(el.querySelector('.report .notice')?.textContent).toContain('already reported'),
    );
  });

  it('shows a removed letter as removed, with its ledger record and no report button', async () => {
    service.get.mockResolvedValue({
      ...LETTER,
      body: null,
      removed: { category: 'harassment', at: '2026-10-02T13:00:00Z' },
    });
    service.verify.mockResolvedValue({
      signatureValid: false,
      ledgerValid: true,
      ledgerProblem: null,
      ledgerCheckpointSize: 31,
    });
    const el: HTMLElement = (await render()).nativeElement;

    expect(el.querySelector('.removed')?.textContent).toContain('Removed by Writeproof');
    expect(el.querySelector('.removed')?.textContent).toContain('2026-10-02 (Harassment)');
    expect(el.querySelector('.body')).toBeNull();
    expect(el.querySelector('.checks')?.textContent).toContain(
      '✓ Its record is in the ledger as entry #9',
    );
    expect(el.textContent).not.toContain('Report this letter');
  });
});
