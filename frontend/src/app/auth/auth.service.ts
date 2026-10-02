import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { fromBase64Url, toBase64Url } from '../crypto/base64url';
import { WalletService } from '../wallet/wallet.service';
import { loginMessage } from './login-message';

export interface Account {
  accountId: string;
  publicKey: string;
  createdAt: string;
}

interface ChallengeResponse {
  challengeId: string;
  nonce: string;
  expiresAt: string;
}

interface TokenResponse {
  token: string;
  expiresAt: string;
}

/** Wallet-based auth: register a public key, then log in by signing a server nonce. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly wallet = inject(WalletService);

  // The access token lives in memory only; logging in again just needs another signature.
  private readonly _token = signal<string | null>(null);
  private readonly _account = signal<Account | null>(null);

  readonly token = this._token.asReadonly();
  readonly account = this._account.asReadonly();
  readonly authenticated = computed(() => this._token() !== null);

  async register(): Promise<Account> {
    return firstValueFrom(
      this.http.post<Account>('/api/accounts', { publicKey: this.requirePublicKey() }),
    );
  }

  async login(): Promise<Account> {
    const publicKey = this.requirePublicKey();
    const challenge = await firstValueFrom(
      this.http.post<ChallengeResponse>('/api/auth/challenge', { publicKey }),
    );
    const message = loginMessage(challenge.challengeId, fromBase64Url(challenge.nonce));
    const signature = toBase64Url(await this.wallet.sign(message));
    const { token } = await firstValueFrom(
      this.http.post<TokenResponse>('/api/auth/verify', {
        challengeId: challenge.challengeId,
        signature,
      }),
    );
    this._token.set(token);

    const account = await firstValueFrom(this.http.get<Account>('/api/me'));
    this._account.set(account);
    return account;
  }

  logout(): void {
    this._token.set(null);
    this._account.set(null);
  }

  private requirePublicKey(): string {
    const publicKey = this.wallet.publicKey();
    if (!publicKey) {
      throw new Error('No wallet is loaded');
    }
    return publicKey;
  }
}
