import { WalletKeys, backupLookupId, decryptBackup, encryptBackup } from './wallet-backup';

const KEYS: WalletKeys = {
  publicKey: 'A'.repeat(43),
  encryptionPublicKey: 'B'.repeat(43),
  identityPkcs8: 'MC4CAQAwBQYDK2VwBCIEI' + 'x'.repeat(22),
  encryptionPkcs8: 'MC4CAQAwBQYDK2VuBCIEI' + 'y'.repeat(22),
};

const code = () => crypto.getRandomValues(new Uint8Array(16));

describe('wallet backup encryption', () => {
  it('decrypts with the right code', async () => {
    const c = code();
    const blob = await encryptBackup(c, KEYS);

    expect(blob).toMatchObject({
      format: 'writeproof.wallet-backup',
      version: 1,
      publicKey: KEYS.publicKey,
    });
    expect(JSON.stringify(blob)).not.toContain(KEYS.identityPkcs8);
    await expect(decryptBackup(c, blob)).resolves.toEqual(KEYS);
  });

  it('fails with the wrong code', async () => {
    const blob = await encryptBackup(code(), KEYS);

    await expect(decryptBackup(code(), blob)).rejects.toThrow(/could not be decrypted/);
  });

  it('fails if the address on the backup was swapped', async () => {
    const c = code();
    const blob = await encryptBackup(c, KEYS);

    await expect(decryptBackup(c, { ...blob, publicKey: 'C'.repeat(43) })).rejects.toThrow(
      /could not be decrypted/,
    );
  });

  it('files the backup under a stable, code-derived id', async () => {
    const c = code();

    expect(await backupLookupId(c)).toBe(await backupLookupId(new Uint8Array(c)));
    expect(await backupLookupId(c)).not.toBe(await backupLookupId(code()));
    expect(await backupLookupId(c)).toMatch(/^[A-Za-z0-9_-]{43}$/);
  });

  it('uses a fresh salt and nonce every time', async () => {
    const c = code();
    const [a, b] = [await encryptBackup(c, KEYS), await encryptBackup(c, KEYS)];

    expect(a.salt).not.toBe(b.salt);
    expect(a.ciphertext).not.toBe(b.ciphertext);
  });
});
