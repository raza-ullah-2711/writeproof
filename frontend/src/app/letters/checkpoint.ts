import { fromBase64Url } from '../crypto/base64url';
import { verifyEd25519 } from '../crypto/ed25519';

/** A signed statement of the ledger's Merkle root at `size` entries. Mirrors backend `Checkpoint`. */
export interface Checkpoint {
  size: number;
  root: string;
  timestampMillis: number;
  signature: string;
}

export const CHECKPOINT_DOMAIN = 'writeproof/checkpoint/v1';

export function checkpointMessage(c: Omit<Checkpoint, 'signature'>): Uint8Array<ArrayBuffer> {
  return new TextEncoder().encode(
    `${CHECKPOINT_DOMAIN}\n${c.size}\n${c.root}\n${c.timestampMillis}`,
  );
}

/** True if the ledger key signed this checkpoint and it is well formed. */
export async function verifyCheckpoint(ledgerKey: string, c: Checkpoint): Promise<boolean> {
  if (!Number.isSafeInteger(c.size) || c.size < 0 || !Number.isSafeInteger(c.timestampMillis)) {
    return false;
  }
  try {
    if (fromBase64Url(c.root).length !== 32) {
      return false;
    }
  } catch {
    return false;
  }
  return verifyEd25519(ledgerKey, checkpointMessage(c), c.signature);
}
