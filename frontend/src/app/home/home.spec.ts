import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { IDBFactory } from 'fake-indexeddb';
import { nextRequest } from '../../testing/http';
import { toBase64Url } from '../crypto/base64url';
import { authInterceptor } from '../auth/auth.interceptor';
import { SystemStatusService } from '../system/system-status.service';
import { Home } from './home';
import { BackupService } from '../wallet/backup.service';
import { WalletService } from '../wallet/wallet.service';

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

  it('explains that registration is closed, and still offers restoring', async () => {
    const system = TestBed.inject(SystemStatusService);
    (system as unknown as { _status: { set(v: unknown): void } })._status.set({
      registrationOpen: false,
      sendingEnabled: true,
      openLettersEnabled: true,
      announcement: '',
    });
    const { el } = await render();

    expect(el.textContent).not.toContain('Create wallet');
    expect(el.querySelector('.closed')?.textContent).toContain("isn't accepting new accounts");
    expect(el.querySelector('form.restore')).not.toBeNull();
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
    (await nextRequest(http, '/api/me')).flush({
      accountId: 'acct-1',
      publicKey,
      createdAt: '',
      encryptionKey: null,
    });
    const keyReq = await nextRequest(http, '/api/me/encryption-key');
    keyReq.flush({ accountId: 'acct-1', publicKey, createdAt: '', ...keyReq.request.body });
    (await nextRequest(http, '/api/me/backup')).flush({ backedUp: false, backedUpAt: null });

    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(el.querySelector('.account-id')?.textContent).toBe('acct-1');
    });
    expect(el.querySelector('.public-key')?.textContent).toBe(publicKey);
  });

  async function signedIn() {
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
    (await nextRequest(http, '/api/me')).flush({
      accountId: 'acct-1',
      publicKey,
      createdAt: '',
      encryptionKey: null,
    });
    const keyReq = await nextRequest(http, '/api/me/encryption-key');
    keyReq.flush({ accountId: 'acct-1', publicKey, createdAt: '', ...keyReq.request.body });
    return { fixture, el, publicKey };
  }

  const buttonNamed = (el: HTMLElement, label: string) =>
    [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === label);

  it('warns when not backed up, then shows a new recovery code once', async () => {
    const { fixture, el } = await signedIn();
    (await nextRequest(http, '/api/me/backup')).flush({ backedUp: false, backedUpAt: null });
    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(el.querySelector('.backup .warn')?.textContent).toContain('Not backed up');
    });

    buttonNamed(el, 'Create recovery code')!.click();
    const put = await nextRequest(http, '/api/me/backup');
    expect(put.request.method).toBe('PUT');
    put.flush({ backedUp: true, backedUpAt: '2026-10-02T12:00:00Z' });
    (await nextRequest(http, '/api/me/backup')).flush({
      backedUp: true,
      backedUpAt: '2026-10-02T12:00:00Z',
    });

    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(el.querySelector('.recovery-code')?.textContent).toMatch(
        /^([0-9A-Z]{4}-){6}[0-9A-Z]{4}$/,
      );
    });
    buttonNamed(el, "I've saved it")!.click();
    await fixture.whenStable();
    expect(el.querySelector('.recovery-code')).toBeNull();
    expect(el.querySelector('.backup .ok')?.textContent).toMatch(/Backed up Oct 2, 2026/);
    expect(buttonNamed(el, 'Replace recovery code')).toBeDefined();
  });

  it('restores a wallet from a recovery code and signs in', async () => {
    // Make a backup on "another device" first.
    const backups = TestBed.inject(BackupService);
    const wallet = TestBed.inject(WalletService);
    await wallet.create();
    const creating = backups.create();
    const put = await nextRequest(http, '/api/me/backup');
    put.flush({ backedUp: true, backedUpAt: '' });
    const code = await creating;
    const { lookupId, blob } = put.request.body;

    // A fresh browser.
    globalThis.indexedDB = new IDBFactory();
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [Home],
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    const { fixture, el } = await render();
    const input = el.querySelector<HTMLInputElement>('input[name=recoveryCode]')!;
    input.value = code;
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    buttonNamed(el, 'Restore wallet')!.click();
    (await nextRequest(http, `/api/backups/${lookupId}`)).flush(blob);
    (await nextRequest(http, '/api/auth/challenge')).flush({
      challengeId: '3f1c2b9e-6a1d-4b8e-9c3f-2d7e5a4b1c0d',
      nonce: toBase64Url(new Uint8Array(32)),
      expiresAt: '',
    });
    (await nextRequest(http, '/api/auth/verify')).flush({ token: 't', expiresAt: '' });
    const restored = TestBed.inject(WalletService);
    (await nextRequest(http, '/api/me')).flush({
      accountId: 'acct-1',
      publicKey: blob.publicKey,
      createdAt: '',
      encryptionKey: restored.encryptionPublicKey(),
    });
    (await nextRequest(http, '/api/me/backup')).flush({ backedUp: true, backedUpAt: '' });

    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(el.querySelector('.public-key')?.textContent).toBe(blob.publicKey);
    });
    expect(restored.publicKey()).toBe(blob.publicKey);
  });

  it('explains a mistyped recovery code', async () => {
    const { fixture, el } = await render();
    const input = el.querySelector<HTMLInputElement>('input[name=recoveryCode]')!;
    input.value = 'ABCD-EFGH-JKMN-PQRS-TVWX-YZ01-2345';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    buttonNamed(el, 'Restore wallet')!.click();

    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(el.querySelector('[role=alert]')?.textContent).toContain('Check for typos');
    });
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
