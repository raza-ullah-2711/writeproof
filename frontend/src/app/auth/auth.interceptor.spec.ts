import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

describe('authInterceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { token: () => 'secret-token' } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
  });

  afterEach(() => http.verify());

  it('adds the bearer token to API calls', () => {
    client.get('/api/me').subscribe();
    expect(http.expectOne('/api/me').request.headers.get('Authorization')).toBe(
      'Bearer secret-token',
    );
  });

  it('never sends the token to other URLs', () => {
    client.get('https://example.com/api/me').subscribe();
    client.get('/actuator/health').subscribe();

    expect(http.expectOne('https://example.com/api/me').request.headers.has('Authorization')).toBe(
      false,
    );
    expect(http.expectOne('/actuator/health').request.headers.has('Authorization')).toBe(false);
  });
});
