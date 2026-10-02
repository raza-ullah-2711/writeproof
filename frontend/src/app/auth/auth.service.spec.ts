import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { IDBFactory } from 'fake-indexeddb';
import { nextRequest } from '../../testing/http';
import { fromBase64Url, toBase64Url } from '../crypto/base64url';
import { WalletService } from '../wallet/wallet.service';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { loginMessage } from './login-message';

describe('AuthService', () => {
  let auth: AuthService;
  let wallet: WalletService;
  let http: HttpTestingController;

  beforeEach(async () => {
    globalThis.indexedDB = new IDBFactory();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    auth = TestBed.inject(AuthService);
    wallet = TestBed.inject(WalletService);
    http = TestBed.inject(HttpTestingController);
    await wallet.create();
  });

  afterEach(() => http.verify());

  it('registers the wallet public key', async () => {
    const registered = auth.register();

    const req = await nextRequest(http, '/api/accounts');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ publicKey: wallet.publicKey() });
    req.flush({
      accountId: 'a1',
      publicKey: wallet.publicKey(),
      createdAt: '2026-01-01T00:00:00Z',
    });

    await expect(registered).resolves.toMatchObject({ accountId: 'a1' });
  });

  it('logs in by signing the server challenge, then sends the token on API calls', async () => {
    const challengeId = '3f1c2b9e-6a1d-4b8e-9c3f-2d7e5a4b1c0d';
    const nonce = crypto.getRandomValues(new Uint8Array(32));
    const loggedIn = auth.login();

    const challengeReq = await nextRequest(http, '/api/auth/challenge');
    expect(challengeReq.request.body).toEqual({ publicKey: wallet.publicKey() });
    challengeReq.flush({ challengeId, nonce: toBase64Url(nonce), expiresAt: '' });

    const verifyReq = await nextRequest(http, '/api/auth/verify');
    const { signature } = verifyReq.request.body as { signature: string };
    const publicKey = await crypto.subtle.importKey(
      'raw',
      fromBase64Url(wallet.publicKey()!),
      { name: 'Ed25519' },
      false,
      ['verify'],
    );
    expect(verifyReq.request.body.challengeId).toBe(challengeId);
    expect(
      await crypto.subtle.verify(
        'Ed25519',
        publicKey,
        fromBase64Url(signature),
        loginMessage(challengeId, nonce),
      ),
    ).toBe(true);
    verifyReq.flush({ token: 'jwt-token', expiresAt: '' });

    const meReq = await nextRequest(http, '/api/me');
    expect(meReq.request.headers.get('Authorization')).toBe('Bearer jwt-token');
    meReq.flush({ accountId: 'a1', publicKey: wallet.publicKey(), createdAt: '' });

    await expect(loggedIn).resolves.toMatchObject({ accountId: 'a1' });
    expect(auth.authenticated()).toBe(true);
    expect(auth.account()?.accountId).toBe('a1');
  });

  it('rejects a challenge with a malformed nonce without signing anything', async () => {
    const signSpy = vi.spyOn(wallet, 'sign');
    const loggedIn = auth.login();

    (await nextRequest(http, '/api/auth/challenge')).flush({
      challengeId: '3f1c2b9e-6a1d-4b8e-9c3f-2d7e5a4b1c0d',
      nonce: toBase64Url(new Uint8Array(8)),
      expiresAt: '',
    });

    await expect(loggedIn).rejects.toThrow(/Nonce/);
    expect(signSpy).not.toHaveBeenCalled();
    expect(auth.authenticated()).toBe(false);
  });

  it('forgets the token on logout', async () => {
    auth.logout();
    expect(auth.token()).toBeNull();
    expect(auth.account()).toBeNull();
  });
});
