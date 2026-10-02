import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { generateRecoveryCode, parseRecoveryCode } from '../crypto/recovery-code';
import { WalletBackupBlob, backupLookupId, decryptBackup, encryptBackup } from './wallet-backup';
import { WalletService } from './wallet.service';

export interface BackupStatus {
  backedUp: boolean;
  backedUpAt: string | null;
}

/**
 * Wallet backup and recovery. The server only ever holds ciphertext, filed under an id derived
 * from the recovery code; the code itself is shown once and never stored or sent.
 */
@Injectable({ providedIn: 'root' })
export class BackupService {
  private readonly http = inject(HttpClient);
  private readonly wallet = inject(WalletService);

  status(): Promise<BackupStatus> {
    return firstValueFrom(this.http.get<BackupStatus>('/api/me/backup'));
  }

  /** Backs the wallet up under a new recovery code (replacing any previous one) and returns it. */
  async create(): Promise<string> {
    const { code, bytes } = await generateRecoveryCode();
    const blob = await encryptBackup(bytes, await this.wallet.exportKeys());
    await firstValueFrom(
      this.http.put<BackupStatus>('/api/me/backup', {
        lookupId: await backupLookupId(bytes),
        blob,
      }),
    );
    return code;
  }

  /** Restores the wallet into this (empty) browser from a recovery code. */
  async restore(codeInput: string): Promise<void> {
    const code = await parseRecoveryCode(codeInput);
    let blob: WalletBackupBlob;
    try {
      blob = await firstValueFrom(
        this.http.get<WalletBackupBlob>(`/api/backups/${await backupLookupId(code)}`),
      );
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 404) {
        throw new Error('No backup was found for this recovery code');
      }
      throw e;
    }
    await this.wallet.restore(await decryptBackup(code, blob));
  }
}
