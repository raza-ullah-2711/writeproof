import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeContext, scribble } from '../../testing/pointer';
import { AuthService } from '../auth/auth.service';
import { HandwritingApi } from '../handwriting/handwriting-api';
import { OpenLetter, OpenLettersService } from '../open-letters/open-letters.service';
import { OpenLettersPage } from './open-letters-page';

const PUBLISHED: OpenLetter = {
  letterHash: 'hash-1',
  author: 'A'.repeat(43),
  sentAt: '2026-10-02T12:00:00.000Z',
  body: 'To everyone',
  signature: 's',
  handwritingHash: 'hw',
  handwritingScore: 0.9,
  ledger: { seq: 7, prevHash: '', payloadHash: 'hash-1', recordedAtMillis: 0, entryHash: 'e' },
  removed: null,
};

describe('OpenLettersPage', () => {
  const authenticated = signal(true);
  let service: { publish: ReturnType<typeof vi.fn>; mine: ReturnType<typeof vi.fn> };
  let enrolment: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    authenticated.set(true);
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(fakeContext() as never);
    vi.spyOn(HTMLCanvasElement.prototype, 'getBoundingClientRect').mockReturnValue(
      new DOMRect(0, 0, 600, 200),
    );
    service = {
      publish: vi.fn().mockResolvedValue(PUBLISHED),
      mine: vi.fn().mockResolvedValue([]),
    };
    enrolment = vi.fn().mockResolvedValue({ enrolled: true, sampleCount: 3, enrolledAt: '' });
    await TestBed.configureTestingModule({
      imports: [OpenLettersPage],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { authenticated } },
        { provide: OpenLettersService, useValue: service },
        { provide: HandwritingApi, useValue: { enrolment } },
      ],
    }).compileComponents();
  });

  afterEach(() => vi.restoreAllMocks());

  async function render() {
    const fixture = TestBed.createComponent(OpenLettersPage);
    await vi.waitFor(async () => {
      await fixture.whenStable();
      if (!fixture.nativeElement.querySelector('.sign, .compose .error, .empty')) {
        throw new Error('not yet');
      }
    });
    return fixture;
  }

  const button = (el: HTMLElement, label: string) =>
    [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === label)!;

  async function write(fixture: ComponentFixture<OpenLettersPage>, confirm: boolean) {
    const el: HTMLElement = fixture.nativeElement;
    const body = el.querySelector<HTMLTextAreaElement>('textarea[name=body]')!;
    body.value = 'To everyone';
    body.dispatchEvent(new Event('input'));
    scribble(el.querySelector('.sign canvas')!);
    if (confirm) {
      el.querySelector<HTMLInputElement>('input[name=understood]')!.click();
    }
    await fixture.whenStable();
  }

  it('needs an explicit confirmation before publishing', async () => {
    const fixture = await render();
    await write(fixture, false);

    expect(button(fixture.nativeElement, 'Sign and publish').disabled).toBe(true);
  });

  it('publishes the hand-signed letter and offers its link', async () => {
    const fixture = await render();
    const el: HTMLElement = fixture.nativeElement;
    await write(fixture, true);
    expect(button(el, 'Sign and publish').disabled).toBe(false);

    service.mine.mockResolvedValue([PUBLISHED]);
    button(el, 'Sign and publish').click();
    await vi.waitFor(async () => {
      await fixture.whenStable();
      if (!el.querySelector('.notice')) throw new Error('not yet');
    });

    const [body, signature] = service.publish.mock.calls[0];
    expect(body).toBe('To everyone');
    expect(signature).toMatchObject({ format: 'writeproof.handwriting' });
    expect(el.querySelector('.notice')?.textContent).toContain('Published as ledger entry #7');
    expect(el.querySelector('.notice a')?.getAttribute('href')).toBe('/open/hash-1');
    await vi.waitFor(() => expect(el.querySelector('.list li a')?.textContent).toBe('To everyone'));
    expect(fixture.componentInstance['link'](PUBLISHED)).toBe(`${location.origin}/open/hash-1`);
  });

  it('asks to enrol handwriting first, and to sign in when signed out', async () => {
    enrolment.mockResolvedValue({ enrolled: false, sampleCount: null, enrolledAt: null });
    let fixture = await render();
    expect(fixture.nativeElement.querySelector('.compose .error')?.textContent).toContain(
      'Enrol your handwriting',
    );

    authenticated.set(false);
    fixture = TestBed.createComponent(OpenLettersPage);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Sign in with your wallet to write one');
    expect(service.mine).toHaveBeenCalledTimes(1);
  });
});
