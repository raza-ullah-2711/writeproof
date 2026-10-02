import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { IDBFactory } from 'fake-indexeddb';
import { nextRequest } from '../../testing/http';
import { parseRecoveryCode } from '../crypto/recovery-code';
import { BackupService } from './backup.service';
import { WalletBackupBlob, backupLookupId, decryptBackup } from './wallet-backup';
import { WalletService } from './wallet.service';

describe('BackupService', () => {
  let http: HttpTestingController;

  function inject() {
    return {
      backups: TestBed.inject(BackupService),
      wallet: TestBed.inject(WalletService),
    };
  }

  beforeEach(() => {
    globalThis.indexedDB = new IDBFactory();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  /** Backs up a new wallet, playing the server; returns the code and what was stored. */
  async function backUpNewWallet() {
    const { backups, wallet } = inject();
    await wallet.create();
    const creating = backups.create();
    const put = await nextRequest(http, '/api/me/backup');
    put.flush({ backedUp: true, backedUpAt: '' });
    const code = await creating;
    return {
      code,
      publicKey: wallet.publicKey()!,
      lookupId: put.request.body.lookupId as string,
      blob: put.request.body.blob as WalletBackupBlob,
    };
  }

  it('uploads only ciphertext, filed under an id derived from the shown code', async () => {
    const { code, publicKey, lookupId, blob } = await backUpNewWallet();
    const bytes = await parseRecoveryCode(code);

    expect(code).toMatch(/^([0-9A-Z]{4}-){6}[0-9A-Z]{4}$/);
    expect(lookupId).toBe(await backupLookupId(bytes));
    expect(blob.publicKey).toBe(publicKey);
    expect(JSON.stringify(blob)).not.toMatch(/MC4CAQAwBQYDK2V/); // no PKCS#8 in the clear
    expect((await decryptBackup(bytes, blob)).publicKey).toBe(publicKey);
  });

  it('restores the wallet on another device from the code alone', async () => {
    const { code, publicKey, lookupId, blob } = await backUpNewWallet();

    // A fresh browser: no wallet, a new TestBed.
    globalThis.indexedDB = new IDBFactory();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    const { backups, wallet } = inject();

    const restoring = backups.restore(code.toLowerCase());
    (await nextRequest(http, `/api/backups/${lookupId}`)).flush(blob);
    await restoring;

    expect(wallet.state()).toBe('ready');
    expect(wallet.publicKey()).toBe(publicKey);
  });

  it('explains when no backup exists for a code', async () => {
    const { backups } = inject();
    const { code } = await backUpNewWallet();
    globalThis.indexedDB = new IDBFactory();

    const restoring = backups.restore(code);
    (
      await nextRequest(http, `/api/backups/${await backupLookupId(await parseRecoveryCode(code))}`)
    ).flush(null, {
      status: 404,
      statusText: 'Not Found',
    });

    await expect(restoring).rejects.toThrow(/No backup was found/);
  });

  it('rejects a mistyped code before contacting the server', async () => {
    const { backups } = inject();

    await expect(backups.restore('ABCD-EFGH-JKMN-PQRS-TVWX-YZ01-2345')).rejects.toThrow(/typos/);
  });
});
