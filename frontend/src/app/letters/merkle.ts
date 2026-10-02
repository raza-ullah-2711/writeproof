/**
 * Verifies proofs about the ledger's Merkle tree, as in Certificate Transparency (RFC 9162 §2.1).
 * Mirrors backend `MerkleTree`; shared test vectors pin both. Sizes are counts of entries and
 * leaf indexes are zero-based (entry #seq is leaf seq - 1).
 */

type Bytes = Uint8Array<ArrayBuffer>;

async function digest(prefix: number, ...parts: Bytes[]): Promise<Bytes> {
  const data = new Uint8Array(1 + parts.reduce((n, p) => n + p.length, 0));
  data[0] = prefix;
  let offset = 1;
  for (const p of parts) {
    data.set(p, offset);
    offset += p.length;
  }
  return new Uint8Array(await crypto.subtle.digest('SHA-256', data));
}

export function leafHash(data: Bytes): Promise<Bytes> {
  return digest(0x00, data);
}

export function nodeHash(left: Bytes, right: Bytes): Promise<Bytes> {
  return digest(0x01, left, right);
}

function equal(a: Bytes, b: Bytes): boolean {
  return a.length === b.length && a.every((x, i) => x === b[i]);
}

const isOdd = (n: number) => n % 2 === 1;
const half = (n: number) => Math.floor(n / 2);

/** RFC 9162 §2.1.3.2: is `leaf` at `index` in the tree of `size` leaves with this `root`? */
export async function verifyInclusion(
  index: number,
  size: number,
  leaf: Bytes,
  proof: readonly Bytes[],
  root: Bytes,
): Promise<boolean> {
  if (!Number.isSafeInteger(index) || !Number.isSafeInteger(size) || index < 0 || index >= size) {
    return false;
  }
  let fn = index;
  let sn = size - 1;
  let r = leaf;
  for (const p of proof) {
    if (sn === 0) {
      return false;
    }
    if (isOdd(fn) || fn === sn) {
      r = await nodeHash(p, r);
      if (!isOdd(fn)) {
        while (!isOdd(fn) && fn !== 0) {
          fn = half(fn);
          sn = half(sn);
        }
      }
    } else {
      r = await nodeHash(r, p);
    }
    fn = half(fn);
    sn = half(sn);
  }
  return sn === 0 && equal(r, root);
}

/** RFC 9162 §2.1.4.2: does the tree of `newSize` leaves extend the one of `oldSize`, unchanged? */
export async function verifyConsistency(
  oldSize: number,
  newSize: number,
  oldRoot: Bytes,
  newRoot: Bytes,
  proof: readonly Bytes[],
): Promise<boolean> {
  if (!Number.isSafeInteger(oldSize) || !Number.isSafeInteger(newSize)) {
    return false;
  }
  if (oldSize < 1 || oldSize > newSize) {
    return false;
  }
  if (oldSize === newSize) {
    return proof.length === 0 && equal(oldRoot, newRoot);
  }
  // When oldSize is a power of two, its root is itself a node of the new tree.
  const path = Number.isInteger(Math.log2(oldSize)) ? [oldRoot, ...proof] : [...proof];
  if (path.length === 0) {
    return false;
  }
  let fn = oldSize - 1;
  let sn = newSize - 1;
  while (isOdd(fn)) {
    fn = half(fn);
    sn = half(sn);
  }
  let fr = path[0];
  let sr = path[0];
  for (const c of path.slice(1)) {
    if (sn === 0) {
      return false;
    }
    if (isOdd(fn) || fn === sn) {
      fr = await nodeHash(c, fr);
      sr = await nodeHash(c, sr);
      if (!isOdd(fn)) {
        while (!isOdd(fn) && fn !== 0) {
          fn = half(fn);
          sn = half(sn);
        }
      }
    } else {
      sr = await nodeHash(sr, c);
    }
    fn = half(fn);
    sn = half(sn);
  }
  return sn === 0 && equal(fr, oldRoot) && equal(sr, newRoot);
}
