import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { Letter, LettersService, OpenedLetter } from '../letters/letters.service';
import { WalletService } from '../wallet/wallet.service';
import { LettersPage } from './letters-page';

const ALICE = 'A'.repeat(43);
const BOB = 'B'.repeat(43);

function letter(id: string): Letter {
  return {
    letterId: id,
    sender: { accountId: 'b', publicKey: BOB },
    recipient: { accountId: 'a', publicKey: ALICE },
    sentAt: '2026-10-02T12:00:00.000Z',
    envelope: {} as Letter['envelope'],
    signature: '',
    letterHash: '',
    ledger: { seq: 4, prevHash: '', payloadHash: '', recordedAtMillis: 0, entryHash: '' },
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

  beforeEach(async () => {
    authenticated.set(true);
    service = {
      inbox: vi.fn().mockResolvedValue([letter('in-1')]),
      sent: vi.fn().mockResolvedValue([]),
      send: vi.fn(),
      open: vi.fn(),
    };
    await TestBed.configureTestingModule({
      imports: [LettersPage],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { authenticated } },
        { provide: WalletService, useValue: { publicKey: () => ALICE } },
        { provide: LettersService, useValue: service },
      ],
    }).compileComponents();
  });

  async function render() {
    const fixture = TestBed.createComponent(LettersPage);
    await settle(fixture, (el) => !el.textContent?.includes('Loading'));
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

  it('opens a letter and shows each verification', async () => {
    const opened: OpenedLetter = {
      body: 'Dear Alice',
      signatureValid: true,
      decrypted: true,
      ledgerValid: false,
      ledgerProblem: 'Chain broken at entry 2: does not link to the previous entry',
    };
    service.open.mockResolvedValue(opened);
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;

    button(el, 'Open and verify').click();
    await settle(fixture, (e) => !!e.querySelector('.checks'));

    expect(el.querySelector('.body')?.textContent).toBe('Dear Alice');
    const checks = [...el.querySelectorAll('.checks li')].map((li) => li.textContent?.trim());
    expect(checks[0]).toContain('✓ Signed by the sender');
    expect(checks[1]).toContain('✓ Sealed for you');
    expect(checks[2]).toContain('✗ In the ledger');
    expect(checks[2]).toContain('Chain broken at entry 2');
  });

  it('sends a letter and switches to the sent box', async () => {
    service.send.mockResolvedValue({
      ...letter('out-1'),
      ledger: { ...letter('x').ledger, seq: 9 },
    });
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;
    const recipient = el.querySelector<HTMLInputElement>('input[name=recipient]')!;
    const body = el.querySelector<HTMLTextAreaElement>('textarea[name=body]')!;
    recipient.value = ` ${BOB} `;
    recipient.dispatchEvent(new Event('input'));
    body.value = 'Dear Bob';
    body.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    button(el, 'Seal and send').click();
    await settle(fixture, (e) => !!e.querySelector('.notice'));

    expect(service.send).toHaveBeenCalledWith(BOB, 'Dear Bob');
    expect(el.querySelector('.notice')?.textContent).toContain('ledger entry #9');
    await vi.waitFor(() => expect(service.sent).toHaveBeenCalled());
  });
});
