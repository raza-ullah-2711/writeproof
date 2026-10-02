import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { fakeContext, scribble } from '../../testing/pointer';
import { AuthService } from '../auth/auth.service';
import { ContactsService } from '../contacts/contacts.service';
import { HandwritingApi } from '../handwriting/handwriting-api';
import { HandwritingSample } from '../handwriting/handwriting-sample';
import { Letter, LettersService, OpenedLetter } from '../letters/letters.service';
import { WalletService } from '../wallet/wallet.service';
import { LettersPage } from './letters-page';

const ALICE = 'A'.repeat(43);
const BOB = 'B'.repeat(43);

const SIGNATURE: HandwritingSample = {
  format: 'writeproof.handwriting',
  version: 1,
  capturedAt: '2026-10-02T12:00:00.000Z',
  device: 'pen',
  width: 600,
  height: 240,
  strokes: [
    [
      { x: 1, y: 1, t: 0, pressure: 0.5, penDown: true },
      { x: 9, y: 9, t: 100, pressure: 0, penDown: false },
    ],
  ],
};

function letter(id: string, handSigned = true): Letter {
  return {
    letterId: id,
    sender: { accountId: 'b', publicKey: BOB },
    recipient: { accountId: 'a', publicKey: ALICE },
    sentAt: '2026-10-02T12:00:00.000Z',
    envelope: {} as Letter['envelope'],
    signature: '',
    letterHash: '',
    ledger: { seq: 4, prevHash: '', payloadHash: '', recordedAtMillis: 0, entryHash: '' },
    handwritingHash: handSigned ? 'h' : null,
    handwritingScore: handSigned ? 0.913 : null,
  };
}

function opened(overrides: Partial<OpenedLetter> = {}): OpenedLetter {
  return {
    body: 'Dear Alice',
    handSigned: true,
    handwriting: SIGNATURE,
    signatureValid: true,
    decrypted: true,
    ledgerValid: true,
    ledgerProblem: null,
    ledgerCheckpointSize: 12,
    ...overrides,
  };
}

describe('LettersPage', () => {
  const authenticated = signal(true);
  let service: {
    inbox: ReturnType<typeof vi.fn>;
    sent: ReturnType<typeof vi.fn>;
    send: ReturnType<typeof vi.fn>;
    open: ReturnType<typeof vi.fn>;
  };
  let enrolment: ReturnType<typeof vi.fn>;
  let petnames: Record<string, string>;
  let queryParams: Record<string, string>;

  beforeEach(async () => {
    authenticated.set(true);
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(fakeContext() as never);
    vi.spyOn(HTMLCanvasElement.prototype, 'getBoundingClientRect').mockReturnValue(
      new DOMRect(0, 0, 600, 200),
    );
    service = {
      inbox: vi.fn().mockResolvedValue([letter('in-1')]),
      sent: vi.fn().mockResolvedValue([]),
      send: vi.fn(),
      open: vi.fn(),
    };
    enrolment = vi.fn().mockResolvedValue({ enrolled: true, sampleCount: 3, enrolledAt: '' });
    petnames = {};
    queryParams = {};
    await TestBed.configureTestingModule({
      imports: [LettersPage],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { authenticated } },
        { provide: WalletService, useValue: { publicKey: () => ALICE } },
        { provide: LettersService, useValue: service },
        { provide: HandwritingApi, useValue: { enrolment } },
        {
          provide: ContactsService,
          useValue: {
            ensureLoaded: vi.fn().mockResolvedValue(undefined),
            petname: (address: string) => petnames[address] ?? null,
            contacts: () =>
              Object.entries(petnames).map(([address, petname]) => ({ address, petname })),
          },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            get snapshot() {
              return { queryParamMap: convertToParamMap(queryParams) };
            },
          },
        },
      ],
    }).compileComponents();
  });

  afterEach(() => vi.restoreAllMocks());

  async function render() {
    const fixture = TestBed.createComponent(LettersPage);
    await settle(
      fixture,
      (el) => !el.textContent?.includes('Loading') && !!el.querySelector('.sign, .compose .error'),
    );
    return fixture;
  }

  async function settle(
    fixture: ComponentFixture<LettersPage>,
    ready: (el: HTMLElement) => boolean,
  ) {
    await vi.waitFor(async () => {
      await fixture.whenStable();
      if (!ready(fixture.nativeElement)) throw new Error('not yet');
    });
  }

  const button = (el: HTMLElement, label: string) =>
    [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === label)!;

  async function compose(fixture: ComponentFixture<LettersPage>, sign = true) {
    const el: HTMLElement = fixture.nativeElement;
    const recipient = el.querySelector<HTMLInputElement>('input[name=recipient]')!;
    const body = el.querySelector<HTMLTextAreaElement>('textarea[name=body]')!;
    recipient.value = ` ${BOB} `;
    recipient.dispatchEvent(new Event('input'));
    body.value = 'Dear Bob';
    body.dispatchEvent(new Event('input'));
    if (sign) {
      scribble(el.querySelector('.sign canvas')!);
    }
    await fixture.whenStable();
  }

  it('asks for a wallet sign-in first', async () => {
    authenticated.set(false);
    const fixture = TestBed.createComponent(LettersPage);
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('Sign in with your wallet first');
    expect(service.inbox).not.toHaveBeenCalled();
  });

  it('shows the address and the inbox', async () => {
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;

    expect(el.querySelector('.address code')?.textContent).toBe(ALICE);
    expect(el.querySelector('.letter .meta')?.textContent).toContain('From BBBBBBBB…BBBB');
    expect(el.querySelector('.letter .meta')?.textContent).toContain('ledger #4');
  });

  it('shows your names for contacts, and offers to add strangers', async () => {
    service.sent.mockResolvedValue([letter('out-1')]);
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;

    const stranger = el.querySelector<HTMLAnchorElement>('.letter .meta a.add-contact')!;
    expect(stranger.textContent).toBe('Add to contacts');
    expect(stranger.getAttribute('href')).toBe(`/contacts?add=${BOB}`);

    petnames[ALICE] = 'Me, on paper';
    petnames[BOB] = 'Bob from choir';
    fixture.componentInstance['selectBox']('sent');
    await settle(fixture, (e) => !!e.querySelector('.letter .petname'));
    expect(el.querySelector('.letter .meta .petname')?.textContent).toBe('Me, on paper');
    expect(el.querySelector('.letter .meta a.add-contact')).toBeNull();
  });

  it('picks the recipient from contacts and names them', async () => {
    petnames[BOB] = 'Bob from choir';
    queryParams = { to: BOB };
    service.send.mockResolvedValue({
      ...letter('out-1'),
      ledger: { ...letter('x').ledger, seq: 9 },
    });
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;

    expect(el.querySelector<HTMLInputElement>('input[name=recipient]')!.value).toBe(BOB);
    expect(el.querySelector('#contact-addresses option')?.textContent?.trim()).toBe(
      'Bob from choir',
    );
    expect(el.querySelector('.recipient-name')?.textContent).toContain('To Bob from choir');

    await compose(fixture);
    button(el, 'Seal and send').click();
    await settle(fixture, (e) => !!e.querySelector('.notice'));
    expect(el.querySelector('.notice')?.textContent).toContain('Sealed for Bob from choir');
  });

  it('asks to enrol handwriting before letters can be sent', async () => {
    enrolment.mockResolvedValue({ enrolled: false, sampleCount: null, enrolledAt: null });
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;
    await compose(fixture, false);

    expect(el.querySelector('.compose .error')?.textContent).toContain('Enrol your handwriting');
    expect(el.querySelector('.sign')).toBeNull();
    expect(button(el, 'Seal and send').disabled).toBe(true);
  });

  it('needs a hand signature before sending', async () => {
    const fixture = await render();
    await compose(fixture, false);

    expect(button(fixture.nativeElement, 'Seal and send').disabled).toBe(true);
  });

  it('sends with the signature, clears the pad and switches to the sent box', async () => {
    service.send.mockResolvedValue({
      ...letter('out-1'),
      ledger: { ...letter('x').ledger, seq: 9 },
    });
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;
    await compose(fixture);
    expect(button(el, 'Seal and send').disabled).toBe(false);

    button(el, 'Seal and send').click();
    await settle(fixture, (e) => !!e.querySelector('.notice'));

    const [to, body, signature] = service.send.mock.calls[0];
    expect([to, body]).toEqual([BOB, 'Dear Bob']);
    expect(signature).toMatchObject({ format: 'writeproof.handwriting', device: 'pen' });
    expect(signature.strokes).toHaveLength(1);
    expect(el.querySelector('.notice')?.textContent).toContain('ledger entry #9');
    expect(button(el, 'Clear signature').disabled).toBe(true); // pad is empty again
    await vi.waitFor(() => expect(service.sent).toHaveBeenCalled());
  });

  it('explains a rejected hand signature and asks for a fresh one', async () => {
    service.send.mockRejectedValue(
      new HttpErrorResponse({
        status: 422,
        error: {
          detail: "Your signature didn't pass the liveness checks",
          score: 0.88,
          livenessFlags: ['REPLAY'],
        },
      }),
    );
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;
    await compose(fixture);

    button(el, 'Seal and send').click();
    await settle(fixture, (e) => !!e.querySelector('[role=alert]'));

    expect(el.querySelector('[role=alert]')?.textContent).toBe(
      "Your signature didn't pass the liveness checks (similarity 0.88). " +
        'Identical to a signature you already used. Write it fresh.',
    );
    expect(button(el, 'Clear signature').disabled).toBe(true);
  });

  it('explains a rejection without a score when the server hides it', async () => {
    service.send.mockRejectedValue(
      new HttpErrorResponse({
        status: 422,
        error: {
          detail: "Your signature didn't match your enrolled handwriting",
          match: false,
          livenessFlags: [],
        },
      }),
    );
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;
    await compose(fixture);

    button(el, 'Seal and send').click();
    await settle(fixture, (e) => !!e.querySelector('[role=alert]'));

    expect(el.querySelector('[role=alert]')?.textContent).toBe(
      "Your signature didn't match your enrolled handwriting.",
    );
  });

  it('opens a hand-signed letter: body, replayable signature and every check', async () => {
    service.open.mockResolvedValue(
      opened({
        ledgerValid: false,
        ledgerProblem: 'The ledger was rewritten since this browser last checked',
        ledgerCheckpointSize: null,
      }),
    );
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;

    button(el, 'Open and verify').click();
    await settle(fixture, (e) => !!e.querySelector('.checks'));

    expect(el.querySelector('.body')?.textContent).toBe('Dear Alice');
    expect(el.querySelector('app-signature-view canvas')).not.toBeNull();
    expect(button(el, 'Replay signature')).toBeDefined();
    const checks = [...el.querySelectorAll('.checks li')].map((li) => li.textContent?.trim());
    expect(checks[0]).toMatch(/^✓ Signed by hand\s+\(similarity 0\.91/);
    expect(checks[1]).toContain("✓ Signed by the sender's wallet");
    expect(checks[2]).toContain('✓ Sealed for you');
    expect(checks[3]).toContain('✗ In the ledger');
    expect(checks[3]).toContain('The ledger was rewritten');
    expect(checks[3]).not.toContain('checkpoint of');
  });

  it('marks letters sent before hand-signing as wallet-signed only', async () => {
    service.inbox.mockResolvedValue([letter('old', false)]);
    service.open.mockResolvedValue(opened({ handSigned: null, handwriting: null }));
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;

    button(el, 'Open and verify').click();
    await settle(fixture, (e) => !!e.querySelector('.checks'));

    expect(el.querySelector('.checks .legacy')?.textContent).toContain('Sent before hand-signing');
    expect(el.querySelector('app-signature-view')).toBeNull();
    expect(el.querySelector('.checks li.ok:last-child')?.textContent).toMatch(
      /✓ In the ledger as entry #4\s+\(proven against a signed checkpoint of 12 entries\)/,
    );
  });
});
