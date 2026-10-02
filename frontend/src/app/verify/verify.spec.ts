import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { nextRequest } from '../../testing/http';
import { fakeContext, scribble } from '../../testing/pointer';
import { AuthService } from '../auth/auth.service';
import { Verify } from './verify';

describe('Verify', () => {
  let http: HttpTestingController;
  const authenticated = signal(true);

  beforeEach(async () => {
    authenticated.set(true);
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(fakeContext() as never);
    vi.spyOn(HTMLCanvasElement.prototype, 'getBoundingClientRect').mockReturnValue(
      new DOMRect(0, 0, 600, 240),
    );
    await TestBed.configureTestingModule({
      imports: [Verify],
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

  async function render(enrolled: boolean) {
    const fixture = TestBed.createComponent(Verify);
    await fixture.whenStable();
    (await nextRequest(http, '/api/handwriting/enrolment')).flush(
      enrolled
        ? { enrolled: true, sampleCount: 3, enrolledAt: '2026-10-02T12:00:00Z' }
        : { enrolled: false, sampleCount: null, enrolledAt: null },
    );
    await settle(fixture);
    return fixture;
  }

  async function settle(fixture: ComponentFixture<Verify>) {
    await vi.waitFor(async () => {
      await fixture.whenStable();
      if (fixture.nativeElement.textContent.includes('Loading')) throw new Error('loading');
    });
  }

  const el = (f: ComponentFixture<Verify>) => f.nativeElement as HTMLElement;
  const button = (f: ComponentFixture<Verify>, label: string) =>
    [...el(f).querySelectorAll('button')].find((b) => b.textContent?.trim() === label)!;
  const canvas = (f: ComponentFixture<Verify>) => el(f).querySelector('canvas')!;

  async function addSamples(fixture: ComponentFixture<Verify>, count: number) {
    for (let i = 0; i < count; i++) {
      scribble(canvas(fixture), i * 1000);
      await fixture.whenStable();
      button(fixture, 'Use this sample').click();
      await fixture.whenStable();
    }
  }

  it('asks for a wallet sign-in first', async () => {
    authenticated.set(false);
    const fixture = TestBed.createComponent(Verify);
    await fixture.whenStable();

    expect(el(fixture).textContent).toContain('Sign in with your wallet first');
  });

  it('collects 3 samples, then enrols them', async () => {
    const fixture = await render(false);
    expect(button(fixture, 'Enrol').disabled).toBe(true);

    await addSamples(fixture, 3);
    expect(el(fixture).querySelector('.progress')?.textContent).toContain('3 of 3');
    button(fixture, 'Enrol').click();

    const req = await nextRequest(http, '/api/handwriting/enrolment');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.samples).toHaveLength(3);
    expect(req.request.body.samples[0].format).toBe('writeproof.handwriting');
    req.flush({ enrolled: true, sampleCount: 3, enrolledAt: '2026-10-02T12:00:00Z' });

    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(el(fixture).textContent).toContain('Enrolled with 3 samples');
    });
  });

  it('drops a sample that fails liveness and explains why', async () => {
    const fixture = await render(false);
    await addSamples(fixture, 3);
    button(fixture, 'Enrol').click();

    (await nextRequest(http, '/api/handwriting/enrolment')).flush(
      { detail: 'x', sampleIndex: 1, livenessFlags: ['CONSTANT_VELOCITY'] },
      { status: 422, statusText: 'Unprocessable Entity' },
    );

    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(el(fixture).querySelector('[role=alert]')?.textContent).toContain(
        'Sample 2 was rejected: Speed was unnaturally constant.',
      );
    });
    expect(el(fixture).querySelector('.progress')?.textContent).toContain('2 of 3');
    expect(button(fixture, 'Enrol').disabled).toBe(true);
  });

  it('shows no score line when the server hides scores', async () => {
    const fixture = await render(true);
    scribble(canvas(fixture));
    await fixture.whenStable();
    button(fixture, 'Verify').click();

    (await nextRequest(http, '/api/handwriting/verify')).flush({
      verified: true,
      score: null,
      threshold: null,
      match: true,
      live: true,
      livenessFlags: [],
      shapeScore: null,
      durationScore: null,
    });

    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(el(fixture).querySelector('.result')?.textContent).toContain('Verified');
    });
    expect(el(fixture).querySelector('.result .score')).toBeNull();
  });

  it('deletes the enrolment after a fresh signature, then offers to enrol again', async () => {
    const fixture = await render(true);
    expect(button(fixture, 'Delete my enrolment').disabled).toBe(true); // must sign first
    scribble(canvas(fixture));
    await fixture.whenStable();
    button(fixture, 'Delete my enrolment').click();

    const deletion = await nextRequest(http, '/api/handwriting/enrolment/deletion');
    expect(deletion.request.body.sample.format).toBe('writeproof.handwriting');
    deletion.flush(null, { status: 204, statusText: 'No Content' });
    (await nextRequest(http, '/api/handwriting/enrolment')).flush({
      enrolled: false,
      sampleCount: null,
      enrolledAt: null,
    });

    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(el(fixture).textContent).toContain('Sign 3 to 5 times');
    });
  });

  it('explains why a deletion signature was refused', async () => {
    const fixture = await render(true);
    scribble(canvas(fixture));
    await fixture.whenStable();
    button(fixture, 'Delete my enrolment').click();

    (await nextRequest(http, '/api/handwriting/enrolment/deletion')).flush(
      {
        detail: "Your signature didn't match your enrolled handwriting",
        match: false,
        livenessFlags: [],
      },
      { status: 422, statusText: 'Unprocessable Entity' },
    );

    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(el(fixture).querySelector('[role=alert]')?.textContent).toBe(
        "Your signature didn't match your enrolled handwriting.",
      );
    });
    expect(el(fixture).textContent).toContain('Enrolled with 3 samples');
  });

  it('verifies a new sample and shows the score and liveness result', async () => {
    const fixture = await render(true);
    scribble(canvas(fixture));
    await fixture.whenStable();
    button(fixture, 'Verify').click();

    const req = await nextRequest(http, '/api/handwriting/verify');
    expect(req.request.body.sample.strokes).toHaveLength(1);
    req.flush({
      verified: false,
      score: 0.91,
      threshold: 0.5,
      match: true,
      live: false,
      livenessFlags: ['REPLAY'],
      shapeScore: 0.91,
      durationScore: 1,
    });

    await vi.waitFor(async () => {
      await fixture.whenStable();
      const result = el(fixture).querySelector('.result')!;
      expect(result.textContent).toContain('Not verified');
      expect(result.textContent).toContain('similarity 0.91 (needs 0.50)');
      expect(result.textContent).toContain('Identical to a signature you already used');
    });
  });
});
