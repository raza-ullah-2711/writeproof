import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { nextRequest } from '../../testing/http';
import { fakeContext, scribble } from '../../testing/pointer';
import { AuthService } from '../auth/auth.service';
import { HandwritingSample } from '../handwriting/handwriting-sample';
import { Calibration } from './calibration';

const TARGET_SAMPLE: HandwritingSample = {
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

describe('Calibration', () => {
  let http: HttpTestingController;
  const authenticated = signal(true);

  beforeEach(async () => {
    authenticated.set(true);
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(fakeContext() as never);
    vi.spyOn(HTMLCanvasElement.prototype, 'getBoundingClientRect').mockReturnValue(
      new DOMRect(0, 0, 600, 200),
    );
    await TestBed.configureTestingModule({
      imports: [Calibration],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { authenticated } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
  });

  const status = (contributing: boolean, genuine = 0, forgeries = 0) => ({
    contributing,
    practiceName: contributing ? 'Ada Quill' : null,
    genuineSamples: genuine,
    forgerySamples: forgeries,
  });

  const el = (f: ComponentFixture<Calibration>) => f.nativeElement as HTMLElement;
  const button = (f: ComponentFixture<Calibration>, label: string) =>
    [...el(f).querySelectorAll('button')].find((b) => b.textContent?.trim() === label)!;

  async function settle(f: ComponentFixture<Calibration>, ready: (e: HTMLElement) => boolean) {
    await vi.waitFor(async () => {
      await f.whenStable();
      if (!ready(el(f))) throw new Error('not yet');
    });
  }

  async function render(contributing: boolean, genuine = 0) {
    const fixture = TestBed.createComponent(Calibration);
    await fixture.whenStable();
    (await nextRequest(http, '/api/calibration')).flush(status(contributing, genuine));
    await settle(fixture, (e) => !e.textContent?.includes('Loading'));
    return fixture;
  }

  it('asks for a wallet sign-in first', async () => {
    authenticated.set(false);
    const fixture = TestBed.createComponent(Calibration);
    await fixture.whenStable();

    expect(el(fixture).textContent).toContain('Sign in with your wallet first');
  });

  it('explains the terms and opts in', async () => {
    const fixture = await render(false);
    expect(el(fixture).querySelector('.terms')?.textContent).toContain(
      'You never give your real signature',
    );

    button(fixture, 'I agree, let me contribute').click();
    const consent = await nextRequest(http, '/api/calibration/consent');
    expect(consent.request.method).toBe('POST');
    consent.flush(status(true));

    await settle(fixture, (e) => !!e.querySelector('.practice-name'));
    expect(el(fixture).querySelector('.practice-name')?.textContent).toBe('Ada Quill');
  });

  it('saves practice samples written in your own style', async () => {
    const fixture = await render(true, 2);
    expect(button(fixture, 'Save practice sample').disabled).toBe(true);
    scribble(el(fixture).querySelector('app-handwriting-pad canvas')!);
    await fixture.whenStable();

    button(fixture, 'Save practice sample').click();
    const save = await nextRequest(http, '/api/calibration/samples');
    expect(save.request.body).toMatchObject({ kind: 'genuine', targetId: null });
    expect(save.request.body.sample.format).toBe('writeproof.handwriting');
    save.flush(status(true, 3));

    await settle(fixture, (e) => !!e.querySelector('.counts')?.textContent?.includes('3 practice'));
    expect(button(fixture, 'Save practice sample').disabled).toBe(true); // pad cleared
  });

  it('shows someone else’s practice name to imitate and saves the imitation', async () => {
    const fixture = await render(true, 5);
    button(fixture, 'Show me one to imitate').click();
    (await nextRequest(http, '/api/calibration/forgery-target')).flush({
      targetId: 'c-123',
      practiceName: 'Tove Kestrel',
      sample: TARGET_SAMPLE,
    });
    await settle(fixture, (e) => !!e.querySelector('app-signature-view'));
    expect(el(fixture).textContent).toContain('Copy this Tove Kestrel');

    const pads = el(fixture).querySelectorAll('app-handwriting-pad canvas');
    scribble(pads[pads.length - 1] as HTMLCanvasElement);
    await fixture.whenStable();
    button(fixture, 'Save imitation').click();

    const save = await nextRequest(http, '/api/calibration/samples');
    expect(save.request.body).toMatchObject({ kind: 'forgery', targetId: 'c-123' });
    save.flush(status(true, 5, 1));
    (await nextRequest(http, '/api/calibration/forgery-target')).flush(null, {
      status: 404,
      statusText: 'Not Found',
    });
    await settle(
      fixture,
      (e) => !!e.querySelector('.counts')?.textContent?.includes('1 imitations'),
    );
  });

  it('says so when there is nothing to imitate yet', async () => {
    const fixture = await render(true, 5);
    button(fixture, 'Show me one to imitate').click();
    (await nextRequest(http, '/api/calibration/forgery-target')).flush(null, {
      status: 404,
      statusText: 'Not Found',
    });

    await settle(
      fixture,
      (e) => !!e.textContent?.includes('No practice signatures to imitate yet'),
    );
    expect(el(fixture).querySelector('[role=alert]')).toBeNull();
  });

  it('withdraws and deletes everything', async () => {
    const fixture = await render(true, 5);

    button(fixture, 'Withdraw and delete my samples').click();
    const withdraw = await nextRequest(http, '/api/calibration');
    expect(withdraw.request.method).toBe('DELETE');
    withdraw.flush(null, { status: 204, statusText: 'No Content' });
    (await nextRequest(http, '/api/calibration')).flush(status(false));

    await settle(fixture, (e) => !!e.querySelector('.terms'));
  });
});
