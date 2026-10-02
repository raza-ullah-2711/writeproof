import { Injectable, computed, inject, signal } from '@angular/core';
import { fromBase64Url, toBase64Url } from '../crypto/base64url';
import { verifyEd25519 } from '../crypto/ed25519';
import { WalletKeys } from './wallet-backup';
import { KeyStore, StoredKey, StoredWallet } from './key-store';

export type WalletState = 'unknown' | 'none' | 'ready';

const WRAP_ALGORITHM = 'AES-GCM';

/**
 * The wallet is the root of trust: an Ed25519 identity keypair generated in the browser,
 * plus an X25519 keypair for receiving sealed letters. Private keys never leave the browser:
 * they are persisted encrypted in IndexedDB and, once unlocked, held in memory only as
 * non-extractable CryptoKeys.
 */
@Injectable({ providedIn: 'root' })
export class WalletService {
  private readonly store = inject(KeyStore);
  private signingKey: CryptoKey | null = null;
  private agreementKey: CryptoKey | null = null;

  private readonly _state = signal<WalletState>('unknown');
  private readonly _publicKey = signal<string | null>(null);
  private readonly _encryptionPublicKey = signal<string | null>(null);

  readonly state = this._state.asReadonly();
  /** Base64url-encoded raw 32-byte Ed25519 public key: the account's address. */
  readonly publicKey = this._publicKey.asReadonly();
  /** Base64url-encoded raw 32-byte X25519 public key. */
  readonly encryptionPublicKey = this._encryptionPublicKey.asReadonly();
  readonly ready = computed(() => this._state() === 'ready');

  /** Loads and unlocks the wallet saved in this browser, if there is one. */
  async load(): Promise<void> {
    const stored = await this.store.load();
    if (!stored) {
      this._state.set('none');
      return;
    }
    let encryption = stored.encryption;
    if (!encryption) {
      // Wallets created before sealed letters existed get their encryption key now.
      encryption = await generateWrapped({ name: 'X25519' }, ['deriveBits'], stored.wrappingKey);
      await this.store.addEncryptionKey(encryption);
    }
    await this.unlock(stored, encryption);
  }

  /** Generates new keypairs and saves them, encrypted, to this browser. */
  async create(): Promise<void> {
    if (await this.store.load()) {
      throw new Error('A wallet already exists in this browser');
    }
    const wrappingKey = await crypto.subtle.generateKey(
      { name: WRAP_ALGORITHM, length: 256 },
      false,
      ['wrapKey', 'unwrapKey'],
    );
    const identity = await generateWrapped({ name: 'Ed25519' }, ['sign', 'verify'], wrappingKey);
    const encryption = await generateWrapped({ name: 'X25519' }, ['deriveBits'], wrappingKey);
    const stored: StoredWallet = {
      version: 1,
      publicKey: identity.publicKey,
      wrappedPrivateKey: identity.wrappedPrivateKey,
      iv: identity.iv,
      wrappingKey,
      createdAt: new Date().toISOString(),
      encryption,
    };
    await this.store.saveNew(stored);
    await this.unlock(stored, encryption);
  }

  /**
   * Exports both private keys for an encrypted backup. They are unwrapped as extractable only
   * here, in memory, and the caller must encrypt them immediately.
   */
  async exportKeys(): Promise<WalletKeys> {
    const stored = await this.store.load();
    if (!stored?.encryption) {
      throw new Error('Wallet is not unlocked');
    }
    const identity = await unwrap(stored, stored, { name: 'Ed25519' }, ['sign'], true);
    const encryption = await unwrap(
      stored,
      stored.encryption,
      { name: 'X25519' },
      ['deriveBits'],
      true,
    );
    return {
      publicKey: toBase64Url(stored.publicKey),
      encryptionPublicKey: toBase64Url(stored.encryption.publicKey),
      identityPkcs8: toBase64Url(new Uint8Array(await crypto.subtle.exportKey('pkcs8', identity))),
      encryptionPkcs8: toBase64Url(
        new Uint8Array(await crypto.subtle.exportKey('pkcs8', encryption)),
      ),
    };
  }

  /**
   * Installs keys from a backup into this browser. Before saving, proves the private keys belong
   * to the public keys the backup names: the identity key signs a challenge that must verify,
   * and the encryption key must agree on a shared secret with a fresh ephemeral key.
   */
  async restore(keys: WalletKeys): Promise<void> {
    if (await this.store.load()) {
      throw new Error('A wallet already exists in this browser');
    }
    const identity = await crypto.subtle.importKey(
      'pkcs8',
      fromBase64Url(keys.identityPkcs8),
      { name: 'Ed25519' },
      true,
      ['sign'],
    );
    const encryption = await crypto.subtle.importKey(
      'pkcs8',
      fromBase64Url(keys.encryptionPkcs8),
      { name: 'X25519' },
      true,
      ['deriveBits'],
    );
    await assertKeysMatch(identity, encryption, keys);

    const wrappingKey = await crypto.subtle.generateKey(
      { name: WRAP_ALGORITHM, length: 256 },
      false,
      ['wrapKey', 'unwrapKey'],
    );
    const identityIv = crypto.getRandomValues(new Uint8Array(12));
    const encryptionIv = crypto.getRandomValues(new Uint8Array(12));
    const stored: StoredWallet = {
      version: 1,
      publicKey: fromBase64Url(keys.publicKey),
      wrappedPrivateKey: await crypto.subtle.wrapKey('pkcs8', identity, wrappingKey, {
        name: WRAP_ALGORITHM,
        iv: identityIv,
      }),
      iv: identityIv,
      wrappingKey,
      createdAt: new Date().toISOString(),
      encryption: {
        publicKey: fromBase64Url(keys.encryptionPublicKey),
        wrappedPrivateKey: await crypto.subtle.wrapKey('pkcs8', encryption, wrappingKey, {
          name: WRAP_ALGORITHM,
          iv: encryptionIv,
        }),
        iv: encryptionIv,
      },
    };
    await this.store.saveNew(stored);
    await this.unlock(stored, stored.encryption!);
  }

  async sign(message: Uint8Array<ArrayBuffer>): Promise<Uint8Array<ArrayBuffer>> {
    if (!this.signingKey) {
      throw new Error('Wallet is not unlocked');
    }
    return new Uint8Array(await crypto.subtle.sign('Ed25519', this.signingKey, message));
  }

  /** X25519 shared secret between this wallet's encryption key and another public key. */
  async ecdh(publicKey: Uint8Array<ArrayBuffer>): Promise<ArrayBuffer> {
    if (!this.agreementKey) {
      throw new Error('Wallet is not unlocked');
    }
    const other = await crypto.subtle.importKey('raw', publicKey, { name: 'X25519' }, false, []);
    return crypto.subtle.deriveBits({ name: 'X25519', public: other }, this.agreementKey, 256);
  }

  private async unlock(stored: StoredWallet, encryption: StoredKey): Promise<void> {
    this.signingKey = await unwrap(stored, stored, { name: 'Ed25519' }, ['sign']);
    this.agreementKey = await unwrap(stored, encryption, { name: 'X25519' }, ['deriveBits']);
    this._publicKey.set(toBase64Url(stored.publicKey));
    this._encryptionPublicKey.set(toBase64Url(encryption.publicKey));
    this._state.set('ready');
  }
}

/** Generates a keypair (extractable only so it can be wrapped once) and wraps the private key. */
async function generateWrapped(
  algorithm: { name: 'Ed25519' | 'X25519' },
  usages: KeyUsage[],
  wrappingKey: CryptoKey,
): Promise<StoredKey> {
  const keyPair = (await crypto.subtle.generateKey(algorithm, true, usages)) as CryptoKeyPair;
  const iv = crypto.getRandomValues(new Uint8Array(12));
  return {
    publicKey: new Uint8Array(await crypto.subtle.exportKey('raw', keyPair.publicKey)),
    wrappedPrivateKey: await crypto.subtle.wrapKey('pkcs8', keyPair.privateKey, wrappingKey, {
      name: WRAP_ALGORITHM,
      iv,
    }),
    iv,
  };
}

function unwrap(
  stored: StoredWallet,
  key: StoredKey,
  algorithm: { name: 'Ed25519' | 'X25519' },
  usages: KeyUsage[],
  extractable = false,
): Promise<CryptoKey> {
  return crypto.subtle.unwrapKey(
    'pkcs8',
    key.wrappedPrivateKey,
    stored.wrappingKey,
    { name: WRAP_ALGORITHM, iv: key.iv },
    algorithm,
    extractable,
    usages,
  );
}

async function assertKeysMatch(
  identity: CryptoKey,
  encryption: CryptoKey,
  keys: WalletKeys,
): Promise<void> {
  const probe = crypto.getRandomValues(new Uint8Array(32));
  const signature = toBase64Url(
    new Uint8Array(await crypto.subtle.sign('Ed25519', identity, probe)),
  );
  if (!(await verifyEd25519(keys.publicKey, probe, signature))) {
    throw new Error("This backup's identity key doesn't match its address");
  }
  const ephemeral = (await crypto.subtle.generateKey({ name: 'X25519' }, false, [
    'deriveBits',
  ])) as CryptoKeyPair;
  const claimed = await crypto.subtle.importKey(
    'raw',
    fromBase64Url(keys.encryptionPublicKey),
    { name: 'X25519' },
    false,
    [],
  );
  const ours = new Uint8Array(
    await crypto.subtle.deriveBits(
      { name: 'X25519', public: ephemeral.publicKey },
      encryption,
      256,
    ),
  );
  const theirs = new Uint8Array(
    await crypto.subtle.deriveBits({ name: 'X25519', public: claimed }, ephemeral.privateKey, 256),
  );
  if (toBase64Url(ours) !== toBase64Url(theirs)) {
    throw new Error("This backup's encryption key doesn't match its public key");
  }
}
