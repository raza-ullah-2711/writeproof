import { Injectable } from '@angular/core';

/**
 * What is persisted in IndexedDB. The Ed25519 private key is stored only as AES-GCM
 * ciphertext (`wrappedPrivateKey`). The wrapping key is a non-extractable CryptoKey:
 * the browser can use it to unwrap, but no script can read its bytes.
 */
export interface StoredWallet {
  version: 1;
  publicKey: Uint8Array<ArrayBuffer>;
  wrappedPrivateKey: ArrayBuffer;
  iv: Uint8Array<ArrayBuffer>;
  wrappingKey: CryptoKey;
  createdAt: string;
  /** X25519 key for sealed letters, wrapped like the identity key. Absent on wallets made before letters. */
  encryption?: StoredKey;
}

export interface StoredKey {
  publicKey: Uint8Array<ArrayBuffer>;
  wrappedPrivateKey: ArrayBuffer;
  iv: Uint8Array<ArrayBuffer>;
}

const DB_NAME = 'writeproof';
const STORE = 'wallet';
const RECORD_KEY = 'primary';

@Injectable({ providedIn: 'root' })
export class KeyStore {
  async load(): Promise<StoredWallet | undefined> {
    const db = await this.open();
    try {
      return await request<StoredWallet | undefined>(
        db.transaction(STORE, 'readonly').objectStore(STORE).get(RECORD_KEY),
      );
    } finally {
      db.close();
    }
  }

  /** Saves the wallet; refuses to overwrite an existing one, since that would destroy an identity. */
  async saveNew(wallet: StoredWallet): Promise<void> {
    const db = await this.open();
    try {
      await request(db.transaction(STORE, 'readwrite').objectStore(STORE).add(wallet, RECORD_KEY));
    } finally {
      db.close();
    }
  }

  /** Adds the encryption key to an existing wallet; never replaces one that is already there. */
  async addEncryptionKey(key: StoredKey): Promise<void> {
    const db = await this.open();
    try {
      const store = db.transaction(STORE, 'readwrite').objectStore(STORE);
      const wallet = await request<StoredWallet | undefined>(store.get(RECORD_KEY));
      if (!wallet) {
        throw new Error('No wallet to add an encryption key to');
      }
      if (wallet.encryption) {
        throw new Error('This wallet already has an encryption key');
      }
      await request(store.put({ ...wallet, encryption: key }, RECORD_KEY));
    } finally {
      db.close();
    }
  }

  private open(): Promise<IDBDatabase> {
    const open = indexedDB.open(DB_NAME, 1);
    open.onupgradeneeded = () => open.result.createObjectStore(STORE);
    return request(open);
  }
}

function request<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}
