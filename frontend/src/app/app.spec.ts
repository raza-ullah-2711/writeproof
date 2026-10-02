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

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('h1')?.textContent).toContain('Writeproof');
  });

  it('shows backend status once the health check resolves', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    http.expectOne('/actuator/health').flush({ status: 'UP' });
    await fixture.whenStable();

    const status = (fixture.nativeElement as HTMLElement).querySelector('.status');
    expect(status?.getAttribute('data-status')).toBe('up');
    expect(status?.textContent).toContain('Backend online');
  });
});
