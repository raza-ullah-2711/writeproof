import { fromBase64Url } from '../crypto/base64url';
import { verifyEd25519 } from '../crypto/ed25519';

/**
 * A handover of the ledger key: the old key signs the new one and the checkpoint (size and root)
 * the new key starts from. Mirrors backend `KeyRotation`; see docs/ledger.md.
 */
export interface KeyRotation {
  oldKey: string;
  newKey: string;
  size: number;
  root: string;
  timestampMillis: number;
  signature: string;
}

export const KEY_ROTATION_DOMAIN = 'writeproof/key-rotation/v1';

export function keyRotationMessage(r: Omit<KeyRotation, 'signature'>): Uint8Array<ArrayBuffer> {
  return new TextEncoder().encode(
    `${KEY_ROTATION_DOMAIN}\n${r.oldKey}\n${r.newKey}\n${r.size}\n${r.root}\n${r.timestampMillis}`,
  );
}

/** True if the old key signed this rotation and it is well formed. */
export async function verifyKeyRotation(r: KeyRotation): Promise<boolean> {
  if (!Number.isSafeInteger(r.size) || r.size < 0 || !Number.isSafeInteger(r.timestampMillis)) {
    return false;
  }
  try {
    if (fromBase64Url(r.root).length !== 32) {
      return false;
    }
  } catch {
    return false;
  }
  return verifyEd25519(r.oldKey, keyRotationMessage(r), r.signature);
}

/**
 * The rotations leading from the `pinned` key to the server's `current` one, each signed by the
 * key before it, oldest first; empty if the key didn't change. Otherwise, why nothing the current
 * key signs can be trusted.
 */
export async function followRotations(
  pinned: string,
  current: string,
  all: KeyRotation[],
): Promise<{ rotations: KeyRotation[] } | { problem: string }> {
  if (pinned === current) {
    return { rotations: [] };
  }
  const start = all.findIndex((r) => r.oldKey === pinned);
  if (start < 0) {
    return {
      problem:
        'The ledger key changed since this browser last checked, so nothing it signs can be trusted',
    };
  }
  const path = all.slice(start);
  let key = pinned;
  let size = 0;
  for (const r of path) {
    if (r.oldKey !== key || !(await verifyKeyRotation(r))) {
      return {
        problem:
          "The ledger key was handed over without the previous key's signature, so nothing it signs can be trusted",
      };
    }
    if (r.size < size) {
      return { problem: "The ledger key handovers go back in time, so they can't be trusted" };
    }
    key = r.newKey;
    size = r.size;
  }
  return key === current
    ? { rotations: path }
    : { problem: "The ledger key handovers don't lead to the ledger's current key" };
}
