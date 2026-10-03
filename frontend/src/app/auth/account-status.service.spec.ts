import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AccountStatusService } from './account-status.service';
import { AuthService } from './auth.service';

describe('AccountStatusService', () => {
  const authenticated = signal(false);

  beforeEach(() => {
    authenticated.set(false);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { authenticated } },
      ],
    });
  });

  it('loads the suspension after sign-in and clears it after sign-out', async () => {
    const status = TestBed.inject(AccountStatusService);
    const http = TestBed.inject(HttpTestingController);
    authenticated.set(true);
    TestBed.tick();
    http.expectOne('/api/me/status').flush({ suspension: { since: 't', reason: 'Spam' } });
    await vi.waitFor(() => expect(status.suspension()?.reason).toBe('Spam'));

    authenticated.set(false);
    TestBed.tick();
    expect(status.suspension()).toBeNull();
    http.verify();
  });
});
