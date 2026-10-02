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
