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
import { verifyEd25519 } from '../crypto/ed25519';
import { encryptionKeyBinding } from '../letters/letter-format';

describe('AuthService', () => {
  const account = (overrides: Record<string, unknown>) => ({
    accountId: 'a1',
    publicKey: TestBed.inject(WalletService).publicKey(),
    createdAt: '',
    encryptionKeySignature: null,
    ...overrides,
  });

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
    meReq.flush(account({ encryptionKey: null }));

    // First login from this wallet: the encryption key is registered, signed by the identity key.
    const keyReq = await nextRequest(http, '/api/me/encryption-key');
    expect(keyReq.request.method).toBe('PUT');
    expect(keyReq.request.body.encryptionKey).toBe(wallet.encryptionPublicKey());
    expect(
      await verifyEd25519(
        wallet.publicKey()!,
        encryptionKeyBinding(wallet.publicKey()!, wallet.encryptionPublicKey()!),
        keyReq.request.body.signature,
      ),
    ).toBe(true);
    keyReq.flush(account({ encryptionKey: wallet.encryptionPublicKey() }));

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

  async function loginUpToMe() {
    const loggedIn = auth.login();
    (await nextRequest(http, '/api/auth/challenge')).flush({
      challengeId: '3f1c2b9e-6a1d-4b8e-9c3f-2d7e5a4b1c0d',
      nonce: toBase64Url(new Uint8Array(32)),
      expiresAt: '',
    });
    (await nextRequest(http, '/api/auth/verify')).flush({ token: 't', expiresAt: '' });
    return { loggedIn, me: await nextRequest(http, '/api/me') };
  }

  it('does not re-register an encryption key that is already registered', async () => {
    const { loggedIn, me } = await loginUpToMe();
    me.flush(account({ encryptionKey: wallet.encryptionPublicKey() }));

    await expect(loggedIn).resolves.toMatchObject({ accountId: 'a1' });
    http.expectNone('/api/me/encryption-key');
  });

  it('refuses an account whose registered encryption key is not this wallet’s', async () => {
    const { loggedIn, me } = await loginUpToMe();
    me.flush(account({ encryptionKey: toBase64Url(new Uint8Array(32)) }));

    await expect(loggedIn).rejects.toThrow(/different encryption key/);
    expect(auth.account()).toBeNull();
  });

  it('forgets the token on logout', async () => {
    auth.logout();
    expect(auth.token()).toBeNull();
    expect(auth.account()).toBeNull();
  });
});
