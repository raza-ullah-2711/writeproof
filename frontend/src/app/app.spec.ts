import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';

describe('App', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('renders the Writeproof title', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    http.expectOne('/actuator/health').flush({ status: 'UP' });
    http.expectOne('/api/system/status').flush({
      registrationOpen: true,
      sendingEnabled: true,
      openLettersEnabled: true,
      announcement: '',
    });

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('h1')?.textContent).toContain('Writeproof');
  });

  it('shows backend status once the health check resolves', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    http.expectOne('/actuator/health').flush({ status: 'UP' });
    http.expectOne('/api/system/status').flush({
      registrationOpen: true,
      sendingEnabled: true,
      openLettersEnabled: true,
      announcement: '',
    });
    await fixture.whenStable();

    const status = (fixture.nativeElement as HTMLElement).querySelector('.status');
    expect(status?.getAttribute('data-status')).toBe('up');
    expect(status?.textContent).toContain('Backend online');
  });

  it('tells a suspended account why, and that reading still works', async () => {
    const { AccountStatusService } = await import('./auth/account-status.service');
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    http.expectOne('/actuator/health').flush({ status: 'UP' });
    http.expectOne('/api/system/status').flush({
      registrationOpen: true,
      sendingEnabled: true,
      openLettersEnabled: true,
      announcement: '',
    });
    // Set after start-up, which (signed out) clears it.
    (
      TestBed.inject(AccountStatusService) as unknown as { _suspension: { set(v: unknown): void } }
    )._suspension.set({
      since: '2026-10-01T00:00:00Z',
      reason: 'Repeated spam reports',
    });
    await fixture.whenStable();

    const banner = (fixture.nativeElement as HTMLElement).querySelector('.suspended');
    expect(banner?.textContent).toContain('Repeated spam reports');
    expect(banner?.textContent).toContain('You can still sign in, read your letters');
  });

  it('shows the announcement to everyone', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    http.expectOne('/actuator/health').flush({ status: 'UP' });
    http.expectOne('/api/system/status').flush({
      registrationOpen: true,
      sendingEnabled: true,
      openLettersEnabled: true,
      announcement: 'Maintenance tonight at 22:00 UTC',
    });
    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('.announcement')?.textContent,
      ).toContain('Maintenance tonight');
    });
  });
});
