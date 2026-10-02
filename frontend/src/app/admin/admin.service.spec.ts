import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { adminGuard } from './admin.guard';
import { AdminService } from './admin.service';

describe('AdminService and adminGuard', () => {
  const authenticated = signal(false);
  let admin: AdminService;
  let http: HttpTestingController;

  beforeEach(() => {
    authenticated.set(false);
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { authenticated } },
      ],
    });
    admin = TestBed.inject(AdminService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const guard = () =>
    TestBed.runInInjectionContext(
      () => adminGuard({} as never, [], {} as never) as Promise<unknown>,
    );

  it('learns the role after sign-in and forgets it after sign-out', async () => {
    TestBed.tick();
    http.expectNone('/api/admin/me');

    authenticated.set(true);
    TestBed.tick();
    http.expectOne('/api/admin/me').flush({ role: 'MODERATOR' });
    await vi.waitFor(() => expect(admin.role()).toBe('MODERATOR'));

    authenticated.set(false);
    TestBed.tick();
    expect(admin.role()).toBeNull();
  });

  it('lets admins and moderators into the admin area', async () => {
    authenticated.set(true);
    TestBed.tick();
    http.expectOne('/api/admin/me').flush({ role: 'ADMIN' });

    const result = guard();
    http.expectOne('/api/admin/me').flush({ role: 'ADMIN' });

    expect(await result).toBe(true);
  });

  it('sends ordinary and signed-out accounts to the wallet page', async () => {
    const router = TestBed.inject(Router);
    expect(router.serializeUrl((await guard()) as never)).toBe('/');

    authenticated.set(true);
    TestBed.tick();
    http.expectOne('/api/admin/me').flush({ role: null });
    const result = guard();
    http.expectOne('/api/admin/me').flush({ role: null });
    expect(router.serializeUrl((await result) as never)).toBe('/');

    const failing = guard();
    http.expectOne('/api/admin/me').flush('', { status: 500, statusText: 'Error' });
    expect(router.serializeUrl((await failing) as never)).toBe('/');
  });

  it('pages the audit log', async () => {
    const first = admin.audit();
    http.expectOne('/api/admin/audit?limit=50').flush([]);
    await first;
    const older = admin.audit(41);
    http.expectOne('/api/admin/audit?limit=50&before=41').flush([]);
    await older;
  });
});
