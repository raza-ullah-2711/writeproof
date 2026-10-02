import { Injectable, computed, inject, signal } from '@angular/core';
import { toBase64Url } from '../crypto/base64url';
import { KeyStore, StoredWallet } from './key-store';

export type WalletState = 'unknown' | 'none' | 'ready';

const WRAP_ALGORITHM = 'AES-GCM';

/**
 * The wallet is the root of trust: an Ed25519 keypair generated in the browser.
 * The private key never leaves the browser — it is persisted encrypted in IndexedDB
 * and, once unlocked, held in memory only as a non-extractable CryptoKey.
 */
@Injectable({ providedIn: 'root' })
export class WalletService {
  private readonly store = inject(KeyStore);
  private signingKey: CryptoKey | null = null;

  private readonly _state = signal<WalletState>('unknown');
  private readonly _publicKey = signal<string | null>(null);

  readonly state = this._state.asReadonly();
  /** Base64url-encoded raw 32-byte Ed25519 public key. */
  readonly publicKey = this._publicKey.asReadonly();
  readonly ready = computed(() => this._state() === 'ready');

  /** Loads and unlocks the wallet saved in this browser, if there is one. */
  async load(): Promise<void> {
    const stored = await this.store.load();
    if (!stored) {
      this._state.set('none');
      return;
    }
    this.signingKey = await unwrapSigningKey(stored);
    this._publicKey.set(toBase64Url(stored.publicKey));
    this._state.set('ready');
  }

  /** Generates a new keypair and saves it, encrypted, to this browser. */
  async create(): Promise<void> {
    if (await this.store.load()) {
      throw new Error('A wallet already exists in this browser');
    }
    // Extractable only so it can be wrapped once; this copy is dropped right after.
    const keyPair = (await crypto.subtle.generateKey({ name: 'Ed25519' }, true, [
      'sign',
      'verify',
    ])) as CryptoKeyPair;
    const wrappingKey = await crypto.subtle.generateKey(
      { name: WRAP_ALGORITHM, length: 256 },
      false,
      ['wrapKey', 'unwrapKey'],
    );
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const stored: StoredWallet = {
      version: 1,
      publicKey: new Uint8Array(await crypto.subtle.exportKey('raw', keyPair.publicKey)),
      wrappedPrivateKey: await crypto.subtle.wrapKey('pkcs8', keyPair.privateKey, wrappingKey, {
        name: WRAP_ALGORITHM,
        iv,
      }),
      iv,
      wrappingKey,
      createdAt: new Date().toISOString(),
    };
    await this.store.saveNew(stored);

    this.signingKey = await unwrapSigningKey(stored);
    this._publicKey.set(toBase64Url(stored.publicKey));
    this._state.set('ready');
  }

  async sign(message: Uint8Array<ArrayBuffer>): Promise<Uint8Array<ArrayBuffer>> {
    if (!this.signingKey) {
      throw new Error('Wallet is not unlocked');
    }
    return new Uint8Array(await crypto.subtle.sign('Ed25519', this.signingKey, message));
  }
}

function unwrapSigningKey(stored: StoredWallet): Promise<CryptoKey> {
  return crypto.subtle.unwrapKey(
    'pkcs8',
    stored.wrappedPrivateKey,
    stored.wrappingKey,
    { name: WRAP_ALGORITHM, iv: stored.iv },
    { name: 'Ed25519' },
    false,
    ['sign'],
  );
}
