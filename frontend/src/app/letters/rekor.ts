import { InjectionToken } from '@angular/core';
import { fromBase64Url, toBase64Url } from '../crypto/base64url';
import { verifyEd25519 } from '../crypto/ed25519';
import { Checkpoint, checkpointMessage, verifyCheckpoint } from './checkpoint';
import { sha256 } from './letter-format';
import { leafHash, verifyInclusion } from './merkle';

type Bytes = Uint8Array<ArrayBuffer>;

/**
 * The public log ledger checkpoints must be anchored in, and how long a checkpoint this browser has
 * seen may go unanchored. Mirrors backend `Rekor`; see docs/ledger.md, "Anchoring in a public log".
 */
export interface PublicLog {
  url: string;
  /** The log's ECDSA P-256 key, SubjectPublicKeyInfo in base64. */
  key: string;
  graceMillis: number;
}

/** Sigstore's public Rekor log, which production deployments anchor in. */
export const SIGSTORE_REKOR: PublicLog = {
  url: 'https://rekor.sigstore.dev',
  key: 'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE2G2Y+2tabdTV5BcGiBIx0a9fAFwrkBbmLSGtks4L3qX6yYY0zufBnhC8Ur/iy55GhWP/9A/bY2LhC30M9+RYtw==',
  graceMillis: 24 * 60 * 60 * 1000,
};

/** The log this build holds the ledger to; null (development) checks no anchors. */
export const PUBLIC_LOG = new InjectionToken<PublicLog | null>('PUBLIC_LOG', {
  providedIn: 'root',
  factory: () => null,
});

export const CHECKPOINT_PAYLOAD_TYPE = 'application/vnd.writeproof.checkpoint+text';

/** The latest anchor as the server relays it from the log (backend `LedgerController`). */
export interface AnchorProof {
  checkpoint: Checkpoint;
  logUrl: string;
  entry: {
    uuid: string;
    logIndex: number;
    /** The canonical entry, base64. */
    body: string;
    proof: {
      logIndex: number;
      treeSize: number;
      rootHash: string;
      hashes: string[];
      checkpoint: string;
    };
  };
}

const SPKI_ED25519 = Uint8Array.from([
  0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
]);

const fromBase64 = (s: string): Bytes => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
const toBase64 = (b: Uint8Array): string => btoa(String.fromCharCode(...b));
const hex = (b: Uint8Array): string =>
  Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
const fromHex = (s: string): Bytes => Uint8Array.from(s.match(/../g) ?? [], (h) => parseInt(h, 16));
const utf8 = (s: string): Bytes => new TextEncoder().encode(s);

/** The one form the ledger key is logged in: PEM of its SubjectPublicKeyInfo, one base64 line. */
export function ledgerKeyPem(ledgerKey: string): string {
  const spki = new Uint8Array(44);
  spki.set(SPKI_ED25519);
  spki.set(fromBase64Url(ledgerKey), 12);
  return `-----BEGIN PUBLIC KEY-----\n${toBase64(spki)}\n-----END PUBLIC KEY-----\n`;
}

/** DSSE's pre-authentication encoding: what the ledger key signs. */
export function pae(type: string, payload: Bytes): Bytes {
  const header = utf8(`DSSEv1 ${utf8(type).length} ${type} ${payload.length} `);
  const out = new Uint8Array(header.length + payload.length);
  out.set(header);
  out.set(payload, header.length);
  return out;
}

/**
 * Null if `a` proves, without trusting the server that relayed it, that `ledgerKey` signed its
 * checkpoint and anchored it in `log`: the checkpoint's signature, the DSSE entry's payload and
 * signature, its inclusion proof, and the log's signed tree head. Otherwise, what failed.
 */
export async function verifyAnchor(
  a: AnchorProof,
  log: PublicLog,
  ledgerKey: string,
): Promise<string | null> {
  if (a.logUrl !== log.url) {
    return `The ledger is anchored in ${a.logUrl}, not in ${log.url}`;
  }
  if (!(await verifyCheckpoint(ledgerKey, a.checkpoint))) {
    return 'The anchored checkpoint is not signed by the ledger key';
  }
  return verifyLogEntry(a.entry, log, ledgerKey, checkpointMessage(a.checkpoint));
}

/** Null if `entry` logs `payload` signed by `ledgerKey`, in the log whose tree head `log.key` signed. */
export async function verifyLogEntry(
  entry: AnchorProof['entry'],
  log: PublicLog,
  ledgerKey: string,
  payload: Bytes,
): Promise<string | null> {
  const a = { entry };
  try {
    const bodyBytes = fromBase64(a.entry.body);
    const body = JSON.parse(new TextDecoder().decode(bodyBytes));
    const spec = body?.spec;
    if (
      body?.kind !== 'dsse' ||
      body?.apiVersion !== '0.0.1' ||
      spec?.payloadHash?.algorithm !== 'sha256' ||
      spec?.payloadHash?.value !== hex(await sha256(payload))
    ) {
      return "The public log's entry doesn't log this checkpoint";
    }
    const verifier = toBase64(utf8(ledgerKeyPem(ledgerKey)));
    const message = pae(CHECKPOINT_PAYLOAD_TYPE, payload);
    let signed = false;
    for (const s of spec.signatures ?? []) {
      if (s.verifier === verifier) {
        signed ||= await verifyEd25519(ledgerKey, message, toBase64Url(fromBase64(s.signature)));
      }
    }
    if (!signed) {
      return "The public log's entry is not signed by the ledger key";
    }
    const leaf = await leafHash(bodyBytes);
    const p = a.entry.proof;
    if (
      !a.entry.uuid.endsWith(hex(leaf)) ||
      !(await verifyInclusion(
        p.logIndex,
        p.treeSize,
        leaf,
        p.hashes.map(fromHex),
        fromHex(p.rootHash),
      ))
    ) {
      return "The public log's entry has no valid inclusion proof";
    }
    return (await verifyTreeHead(p.checkpoint, log.key, p.treeSize, fromHex(p.rootHash)))
      ? null
      : "The public log's tree head is not signed by the log's key";
  } catch {
    return "The public log's entry could not be read";
  }
}

/** The log's signed note: "origin\nsize\nbase64(root)\n\n— name base64(keyHint ‖ DER signature)". */
async function verifyTreeHead(note: string, logKey: string, size: number, root: Bytes) {
  const split = note.indexOf('\n\n');
  const text = note.slice(0, split + 1);
  const lines = text.split('\n');
  if (split < 0 || lines[1] !== String(size) || hex(fromBase64(lines[2])) !== hex(root)) {
    return false;
  }
  const key = await crypto.subtle.importKey(
    'spki',
    fromBase64(logKey),
    { name: 'ECDSA', namedCurve: 'P-256' },
    false,
    ['verify'],
  );
  for (const line of note.slice(split + 2).split('\n')) {
    if (!line.startsWith('— ')) {
      continue;
    }
    const sig = fromBase64(line.slice(line.lastIndexOf(' ') + 1)).slice(4);
    const raw = derToRaw(sig);
    if (
      raw &&
      (await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, raw, utf8(text)))
    ) {
      return true;
    }
  }
  return false;
}

/** ECDSA signatures come DER-encoded; WebCrypto wants r ‖ s, 32 bytes each. */
function derToRaw(der: Bytes): Bytes | null {
  if (der[0] !== 0x30) {
    return null;
  }
  const out = new Uint8Array(64);
  let i = 2;
  for (const offset of [0, 32]) {
    if (der[i] !== 0x02) {
      return null;
    }
    let length = der[i + 1];
    let start = i + 2;
    while (length > 32 && der[start] === 0) {
      start++;
      length--;
    }
    if (length > 32) {
      return null;
    }
    out.set(der.subarray(start, start + length), offset + 32 - length);
    i = i + 2 + der[i + 1];
  }
  return out;
}
