import { toBase64Url } from '../crypto/base64url';
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
