import { TestBed } from '@angular/core/testing';
import { IDBFactory } from 'fake-indexeddb';
import { fromBase64Url } from '../crypto/base64url';
import { KeyStore } from './key-store';
import { WalletService } from './wallet.service';

describe('WalletService', () => {
  beforeEach(() => {
    globalThis.indexedDB = new IDBFactory();
  });

  function freshService(): WalletService {
    TestBed.resetTestingModule();
    return TestBed.inject(WalletService);
  }

  it('reports no wallet in a fresh browser', async () => {
    const wallet = freshService();
    await wallet.load();

    expect(wallet.state()).toBe('none');
    expect(wallet.publicKey()).toBeNull();
  });

  it('creates an Ed25519 wallet whose signatures verify against its public key', async () => {
    const wallet = freshService();
    await wallet.create();

    const message = new TextEncoder().encode('hello');
    const signature = await wallet.sign(message);
    const publicKey = await crypto.subtle.importKey(
      'raw',
      fromBase64Url(wallet.publicKey()!),
      { name: 'Ed25519' },
      false,
      ['verify'],
    );

    expect(wallet.state()).toBe('ready');
    expect(fromBase64Url(wallet.publicKey()!)).toHaveLength(32);
    expect(await crypto.subtle.verify('Ed25519', publicKey, signature, message)).toBe(true);
  });

  it('persists the wallet so a later session can unlock and sign with the same key', async () => {
    const first = freshService();
    await first.create();
    const publicKey = first.publicKey();

    const second = freshService();
    await second.load();

    expect(second.state()).toBe('ready');
    expect(second.publicKey()).toBe(publicKey);
    await expect(second.sign(new TextEncoder().encode('x'))).resolves.toHaveLength(64);
  });

  it('stores the private key only as ciphertext under a non-extractable wrapping key', async () => {
    const wallet = freshService();
    await wallet.create();

    const stored = (await TestBed.inject(KeyStore).load())!;
    const record = stored as unknown as Record<string, unknown>;

    expect(stored.wrappingKey.extractable).toBe(false);
    expect(stored.wrappedPrivateKey.byteLength).toBeGreaterThan(48); // pkcs8 (48 B) + GCM tag
    expect(Object.values(record).some((v) => v instanceof CryptoKey && v.type === 'private')).toBe(
      false,
    );
  });

  it('never overwrites an existing wallet', async () => {
    const wallet = freshService();
    await wallet.create();

    await expect(freshService().create()).rejects.toThrow(/already exists/);
  });

  it('refuses to sign before a wallet is unlocked', async () => {
    await expect(freshService().sign(new Uint8Array([1]))).rejects.toThrow(/not unlocked/);
  });
});
