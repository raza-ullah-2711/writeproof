import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { loginMessage } from '@app/auth/login-message';
import { fromBase64Url, toBase64Url } from '@app/crypto/base64url';
import { WalletService } from '@app/wallet/wallet.service';

interface ChallengeResponse {
  challengeId: string;
  nonce: string;
}

/**
 * Signs this origin's wallet in to the admin app. The admin host's proxy marks these requests, so
 * the server issues an admin-app token, and only to admins and moderators (403 otherwise). That
 * token works only on the admin API: the public app's sign-in (AuthService) is never used here.
 */
@Injectable({ providedIn: 'root' })
export class AdminAuth {
  private readonly http = inject(HttpClient);
  private readonly wallet = inject(WalletService);

  private readonly _token = signal<string | null>(null);
  readonly token = this._token.asReadonly();
  readonly authenticated = computed(() => this._token() !== null);

  async login(): Promise<void> {
    const publicKey = this.wallet.publicKey();
    if (!publicKey) {
      throw new Error('No wallet on this device');
    }
    const challenge = await firstValueFrom(
      this.http.post<ChallengeResponse>('/api/auth/challenge', { publicKey }),
    );
    const message = loginMessage(challenge.challengeId, fromBase64Url(challenge.nonce));
    const signature = toBase64Url(await this.wallet.sign(message));
    const { token } = await firstValueFrom(
      this.http.post<{ token: string }>('/api/auth/verify', {
        challengeId: challenge.challengeId,
        signature,
      }),
    );
    this._token.set(token);
  }

  logout(): void {
    this._token.set(null);
  }
}
