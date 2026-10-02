import { fromBase64Url, toBase64Url } from '../crypto/base64url';
import { LEDGER_DOMAIN, sha256 } from './letter-format';

export interface LedgerEntry {
  seq: number;
  prevHash: string;
  payloadHash: string;
  recordedAtMillis: number;
  entryHash: string;
}

export const GENESIS_PREV_HASH = toBase64Url(new Uint8Array(32));

/** Mirrors backend `LedgerHashing.entryHash`. */
export async function entryHash(
  seq: number,
  prevHash: string,
  payloadHash: string,
  recordedAtMillis: number,
): Promise<string> {
  const preimage = `${LEDGER_DOMAIN}\n${seq}\n${prevHash}\n${payloadHash}\n${recordedAtMillis}`;
  return toBase64Url(await sha256(new TextEncoder().encode(preimage)));
}

export type ChainResult = { intact: true } | { intact: false; brokenAt: number; reason: string };

/**
 * Walks the chain from the genesis entry: every entry must have the next sequence number,
 * link to its predecessor's hash, and hash to what it claims.
 */
export async function verifyChain(entries: readonly LedgerEntry[]): Promise<ChainResult> {
  let prev = GENESIS_PREV_HASH;
  for (let i = 0; i < entries.length; i++) {
    const e = entries[i];
    if (e.seq !== i + 1) {
      return { intact: false, brokenAt: i + 1, reason: 'missing or out-of-order entry' };
    }
    if (e.prevHash !== prev) {
      return { intact: false, brokenAt: e.seq, reason: 'does not link to the previous entry' };
    }
    if ((await entryHash(e.seq, e.prevHash, e.payloadHash, e.recordedAtMillis)) !== e.entryHash) {
      return { intact: false, brokenAt: e.seq, reason: 'hash does not match its contents' };
    }
    if (fromBase64Url(e.payloadHash).length !== 32) {
      return { intact: false, brokenAt: e.seq, reason: 'payload is not a hash' };
    }
    prev = e.entryHash;
  }
  return { intact: true };
}
