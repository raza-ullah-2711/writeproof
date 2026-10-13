import { sha256 } from '../letters/letter-format';

/**
 * Canonical bytes of a private family-archive entry (docs/family-archive.md). Mirrors backend
 * `ArchiveHashing`; pinned by a shared test vector.
 */
export const ARCHIVE_DOMAIN = 'writeproof/archive/v1';

/** One reader's copy of the content key, wrapped to their encryption key. */
export interface ArchiveReader {
  /** Their address (identity key). */
  publicKey: string;
  ephemeralPublicKey: string;
  iv: string;
  wrappedKey: string;
}

const utf8 = (text: string) => new TextEncoder().encode(text);

/** Binds the owner, the time and the exact encrypted photos, in order; also the description's AAD. */
export function archiveHeader(owner: string, addedAt: string, photos: readonly string[]): string {
  return `${ARCHIVE_DOMAIN}\n${owner}\n${addedAt}\n${photos.join(',')}`;
}

/** What the ledger records and the owner's wallet signs: the header, description and every wrap. */
export function archiveEntryHash(
  header: string,
  iv: string,
  ciphertext: string,
  readers: readonly ArchiveReader[],
): Promise<Uint8Array<ArrayBuffer>> {
  const parts = [header, iv, ciphertext];
  for (const r of readers) {
    parts.push(r.publicKey, r.ephemeralPublicKey, r.iv, r.wrappedKey);
  }
  return sha256(utf8(parts.join('\n')));
}

/** Associated data for photo `index`: a photo can't be moved to another entry or position. */
export function photoAad(owner: string, addedAt: string, index: number): Uint8Array<ArrayBuffer> {
  return utf8(`writeproof/archive-photo/v1\n${owner}\n${addedAt}\n${index}`);
}
