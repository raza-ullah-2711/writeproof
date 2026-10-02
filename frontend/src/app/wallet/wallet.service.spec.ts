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

  it('creates an X25519 encryption key alongside the identity key', async () => {
    const alice = freshService();
    await alice.create();
    const other = (await crypto.subtle.generateKey({ name: 'X25519' }, false, [
      'deriveBits',
    ])) as CryptoKeyPair;
    const otherPublic = new Uint8Array(await crypto.subtle.exportKey('raw', other.publicKey));
    const alicePublic = await crypto.subtle.importKey(
      'raw',
      fromBase64Url(alice.encryptionPublicKey()!),
      { name: 'X25519' },
      false,
      [],
    );

    const mine = new Uint8Array(await alice.ecdh(otherPublic));
    const theirs = new Uint8Array(
      await crypto.subtle.deriveBits(
        { name: 'X25519', public: alicePublic },
        other.privateKey,
        256,
      ),
    );

    expect(mine).toEqual(theirs);
    expect((await TestBed.inject(KeyStore).load())!.encryption).toBeDefined();
  });

  it('adds an encryption key to a wallet created before letters existed', async () => {
    const wallet = freshService();
    await wallet.create();
    const store = TestBed.inject(KeyStore);
    const stored = (await store.load())!;
    // Recreate the pre-letters record (no encryption key).
    globalThis.indexedDB = new IDBFactory();
    const { encryption: _, ...legacy } = stored;
    await TestBed.inject(KeyStore).saveNew(legacy);

    const upgraded = freshService();
    await upgraded.load();

    expect(upgraded.publicKey()).toBe(wallet.publicKey());
    expect(upgraded.encryptionPublicKey()).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect((await TestBed.inject(KeyStore).load())!.encryption).toBeDefined();
    const again = freshService();
    await again.load();
    expect(again.encryptionPublicKey()).toBe(upgraded.encryptionPublicKey());
  });

  it('exports keys that restore the same wallet in a fresh browser', async () => {
    const original = freshService();
    await original.create();
    const keys = await original.exportKeys();

    globalThis.indexedDB = new IDBFactory(); // a different device
    const restored = freshService();
    await restored.restore(keys);

    expect(restored.publicKey()).toBe(original.publicKey());
    expect(restored.encryptionPublicKey()).toBe(original.encryptionPublicKey());
    const message = new TextEncoder().encode('same identity');
    const publicKey = await crypto.subtle.importKey(
      'raw',
      fromBase64Url(original.publicKey()!),
      { name: 'Ed25519' },
      false,
      ['verify'],
    );
    expect(
      await crypto.subtle.verify('Ed25519', publicKey, await restored.sign(message), message),
    ).toBe(true);
    // And it survives a reload, stored encrypted like any other wallet.
    const reloaded = freshService();
    await reloaded.load();
    expect(reloaded.publicKey()).toBe(original.publicKey());
    expect((await TestBed.inject(KeyStore).load())!.wrappingKey.extractable).toBe(false);
  });

  it('refuses to restore keys that do not match the backed-up address', async () => {
    const a = freshService();
    await a.create();
    const keysA = await a.exportKeys();
    globalThis.indexedDB = new IDBFactory();
    const b = freshService();
    await b.create();
    const keysB = await b.exportKeys();

    globalThis.indexedDB = new IDBFactory();
    await expect(
      freshService().restore({ ...keysA, identityPkcs8: keysB.identityPkcs8 }),
    ).rejects.toThrow(/identity key doesn't match/);
    await expect(
      freshService().restore({ ...keysA, encryptionPkcs8: keysB.encryptionPkcs8 }),
    ).rejects.toThrow(/encryption key doesn't match/);
    expect(await TestBed.inject(KeyStore).load()).toBeUndefined();
  });

  it('never restores over an existing wallet', async () => {
    const wallet = freshService();
    await wallet.create();

    await expect(freshService().restore(await wallet.exportKeys())).rejects.toThrow(
      /already exists/,
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
