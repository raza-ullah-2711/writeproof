import { toBase64Url } from '../crypto/base64url';
import { PUBLIC_REKOR_ENTRY } from '../../testing/rekor-public-entry';
import { SIGSTORE_REKOR, ledgerKeyPem, verifyLogEntry } from './rekor';

const PAYLOAD = new TextEncoder().encode(
  'writeproof/checkpoint/v1\n0\n47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU\n1791036000000',
);

/** The throwaway Ed25519 key the real entry was logged under, as the app writes keys. */
function loggedKey(): { key: string; verifier: string } {
  const body = JSON.parse(atob(PUBLIC_REKOR_ENTRY.body));
  const verifier: string = body.spec.signatures[0].verifier;
  const spki = Uint8Array.from(
    atob(
      atob(verifier)
        .replace(/-----[A-Z ]+-----/g, '')
        .replace(/\s/g, ''),
    ),
    (c) => c.charCodeAt(0),
  );
  return { key: toBase64Url(spki.slice(-32)), verifier };
}

describe('anchors in the public log', () => {
  it('verifies a real entry from rekor.sigstore.dev against its pinned key', async () => {
    expect(
      await verifyLogEntry(PUBLIC_REKOR_ENTRY, SIGSTORE_REKOR, loggedKey().key, PAYLOAD),
    ).toBeNull();
  });

  it('writes the ledger key exactly as it was logged (and as the backend writes it)', () => {
    const { key, verifier } = loggedKey();
    expect(btoa(ledgerKeyPem(key))).toBe(verifier);
  });

  it('rejects an entry that does not prove its claim', async () => {
    const { key } = loggedKey();
    const other = PAYLOAD.slice();
    other[other.length - 1] ^= 1;
    expect(await verifyLogEntry(PUBLIC_REKOR_ENTRY, SIGSTORE_REKOR, key, other)).toMatch(
      /doesn't log this checkpoint/,
    );

    const hashes = [...PUBLIC_REKOR_ENTRY.proof.hashes];
    hashes[0] = '00'.repeat(32);
    const badProof = { ...PUBLIC_REKOR_ENTRY, proof: { ...PUBLIC_REKOR_ENTRY.proof, hashes } };
    expect(await verifyLogEntry(badProof, SIGSTORE_REKOR, key, PAYLOAD)).toMatch(
      /no valid inclusion proof/,
    );

    const pair = (await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, [
      'sign',
      'verify',
    ])) as CryptoKeyPair;
    const otherLogKey = btoa(
      String.fromCharCode(...new Uint8Array(await crypto.subtle.exportKey('spki', pair.publicKey))),
    );
    expect(
      await verifyLogEntry(
        PUBLIC_REKOR_ENTRY,
        { ...SIGSTORE_REKOR, key: otherLogKey },
        key,
        PAYLOAD,
      ),
    ).toMatch(/not signed by the log's key/);
  });
});
