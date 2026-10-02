import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { IDBFactory } from 'fake-indexeddb';
import { nextRequest } from '../../testing/http';
import { toBase64Url } from '../crypto/base64url';
import { authInterceptor } from '../auth/auth.interceptor';
import { Home } from './home';

describe('Home', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    globalThis.indexedDB = new IDBFactory();
    await TestBed.configureTestingModule({
      imports: [Home],
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render() {
    const fixture = TestBed.createComponent(Home);
    const el = fixture.nativeElement as HTMLElement;
    await vi.waitFor(async () => {
      await fixture.whenStable();
      if (el.textContent?.includes('Opening wallet')) throw new Error('still loading');
    });
    return { fixture, el };
  }

  it('offers to create a wallet when none exists', async () => {
    const { el } = await render();
    expect(el.querySelector('button')?.textContent).toContain('Create wallet');
  });

  it('creates, registers and signs in a new wallet', async () => {
    const { fixture, el } = await render();
    el.querySelector('button')!.click();

    const register = await nextRequest(http, '/api/accounts');
    const publicKey = register.request.body.publicKey as string;
    register.flush({ accountId: 'acct-1', publicKey, createdAt: '' });
    (await nextRequest(http, '/api/auth/challenge')).flush({
      challengeId: '3f1c2b9e-6a1d-4b8e-9c3f-2d7e5a4b1c0d',
      nonce: toBase64Url(new Uint8Array(32)),
      expiresAt: '',
    });
    (await nextRequest(http, '/api/auth/verify')).flush({ token: 't', expiresAt: '' });
    (await nextRequest(http, '/api/me')).flush({ accountId: 'acct-1', publicKey, createdAt: '' });

    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(el.querySelector('.account-id')?.textContent).toBe('acct-1');
    });
    expect(el.querySelector('.public-key')?.textContent).toBe(publicKey);
  });

  it('shows an error when the server is unreachable', async () => {
    const { fixture, el } = await render();
    el.querySelector('button')!.click();

    (await nextRequest(http, '/api/accounts')).error(new ProgressEvent('error'), { status: 0 });

    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(el.querySelector('[role=alert]')?.textContent).toContain('unreachable');
    });
  });
});
