import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { BackupService } from '@app/wallet/backup.service';
import { WalletService } from '@app/wallet/wallet.service';
import { adminAuthInterceptor } from './admin-auth.interceptor';
import { AdminAuth } from './admin-auth.service';
import { AdminSignIn } from './admin-sign-in';

const flushMicrotasks = () => new Promise((resolve) => setTimeout(resolve, 0));

describe('admin sign-in', () => {
  const state = signal<'unknown' | 'none' | 'ready'>('none');
  const publicKey = signal<string | null>(null);
  const restore = vi.fn(async () => {
    state.set('ready');
    publicKey.set('AAAA');
  });
  let http: HttpTestingController;
  let auth: AdminAuth;

  beforeEach(() => {
    state.set('none');
    publicKey.set(null);
    restore.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(withInterceptors([adminAuthInterceptor])),
        provideHttpClientTesting(),
        {
          provide: WalletService,
          useValue: {
            state,
            publicKey,
            load: async () => {},
            sign: async () => new Uint8Array(64),
          },
        },
        { provide: BackupService, useValue: { restore } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AdminAuth);
  });

  afterEach(() => http.verify());

  /** Answers the challenge and the verify request; `verify` decides how the server answers. */
  async function answerLogin(
    verify: (req: ReturnType<HttpTestingController['expectOne']>) => void,
  ) {
    await flushMicrotasks();
    http
      .expectOne('/api/auth/challenge')
      .flush({ challengeId: '00000000-0000-0000-0000-000000000001', nonce: 'A'.repeat(43) });
    await flushMicrotasks();
    verify(http.expectOne('/api/auth/verify'));
  }

  it('signs the wallet in and attaches the token to API calls only', async () => {
    publicKey.set('AAAA');
    const login = auth.login();
    await answerLogin((req) => req.flush({ token: 't1' }));
    await login;

    expect(auth.authenticated()).toBe(true);
    const client = TestBed.inject(HttpClient);
    void firstValueFrom(client.get('/api/admin/me'));
    void firstValueFrom(client.get('https://elsewhere.example/x'));
    expect(http.expectOne('/api/admin/me').request.headers.get('Authorization')).toBe('Bearer t1');
    expect(http.expectOne('https://elsewhere.example/x').request.headers.has('Authorization')).toBe(
      false,
    );
  });

  it('forgets a token the server rejects', async () => {
    publicKey.set('AAAA');
    const login = auth.login();
    await answerLogin((req) => req.flush({ token: 't1' }));
    await login;

    const call = firstValueFrom(TestBed.inject(HttpClient).get('/api/admin/dashboard'));
    http.expectOne('/api/admin/dashboard').flush({}, { status: 401, statusText: 'Unauthorized' });
    await expect(call).rejects.toBeTruthy();
    expect(auth.authenticated()).toBe(false);
  });

  it('restores the wallet from a recovery code, signs in and enters the admin area', async () => {
    const fixture = TestBed.createComponent(AdminSignIn);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const input: HTMLInputElement = fixture.nativeElement.querySelector('input');
    input.value = 'my code';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    await answerLogin((req) => req.flush({ token: 't1' }));

    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith('/admin'));
    expect(restore).toHaveBeenCalledWith('my code');
    expect(auth.authenticated()).toBe(true);
  });

  it('says so when the wallet is not an admin', async () => {
    state.set('ready');
    publicKey.set('AAAA');
    const fixture = TestBed.createComponent(AdminSignIn);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    await answerLogin((req) => req.flush({}, { status: 403, statusText: 'Forbidden' }));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('not an admin or moderator');
    expect(auth.authenticated()).toBe(false);
  });
});
