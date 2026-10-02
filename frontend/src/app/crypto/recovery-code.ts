/**
 * Recovery codes: 128 random bits as 26 Crockford base32 characters plus a 2-character
 * checksum, shown in groups of four (`XXXX-XXXX-…`, 7 groups). Crockford's alphabet has no
 * I, L, O or U, and parsing is forgiving: case, spaces and dashes are ignored, and O/I/L are
 * read as 0/1/1. The checksum (first 10 bits of SHA-256) catches typos before any lookup.
 */
const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
const CODE_BYTES = 16;
const DATA_CHARS = 26; // ceil(128 / 5)
const CHECK_CHARS = 2;

export async function generateRecoveryCode(): Promise<{
  code: string;
  bytes: Uint8Array<ArrayBuffer>;
}> {
  const bytes = crypto.getRandomValues(new Uint8Array(CODE_BYTES));
  return { code: await formatRecoveryCode(bytes), bytes };
}

export async function formatRecoveryCode(bytes: Uint8Array): Promise<string> {
  if (bytes.length !== CODE_BYTES) {
    throw new Error('A recovery code is 16 bytes');
  }
  const chars = encode(toBigInt(bytes), DATA_CHARS) + (await checksum(bytes));
  return chars.match(/.{1,4}/g)!.join('-');
}

/** @throws Error with a user-facing message if the code is mistyped */
export async function parseRecoveryCode(input: string): Promise<Uint8Array<ArrayBuffer>> {
  const chars = input.toUpperCase().replace(/[\s-]/g, '').replace(/O/g, '0').replace(/[IL]/g, '1');
  if (chars.length !== DATA_CHARS + CHECK_CHARS) {
    throw new Error(`A recovery code has ${DATA_CHARS + CHECK_CHARS} characters`);
  }
  if ([...chars].some((c) => !ALPHABET.includes(c))) {
    throw new Error('That recovery code contains characters it can’t have');
  }
  const value = decode(chars.slice(0, DATA_CHARS));
  if (value >> 128n !== 0n) {
    throw new Error('That recovery code isn’t valid. Check for typos');
  }
  const bytes = fromBigInt(value);
  if ((await checksum(bytes)) !== chars.slice(DATA_CHARS)) {
    throw new Error('That recovery code isn’t valid. Check for typos');
  }
  return bytes;
}

async function checksum(bytes: Uint8Array<ArrayBuffer> | Uint8Array): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', new Uint8Array(bytes)));
  const tenBits = (digest[0] << 2) | (digest[1] >> 6);
  return ALPHABET[tenBits >> 5] + ALPHABET[tenBits & 31];
}

function encode(value: bigint, length: number): string {
  let out = '';
  for (let i = 0; i < length; i++) {
    out = ALPHABET[Number(value & 31n)] + out;
    value >>= 5n;
  }
  return out;
}

function decode(chars: string): bigint {
  let value = 0n;
  for (const c of chars) {
    value = (value << 5n) | BigInt(ALPHABET.indexOf(c));
  }
  return value;
}

function toBigInt(bytes: Uint8Array): bigint {
  let value = 0n;
  for (const b of bytes) {
    value = (value << 8n) | BigInt(b);
  }
  return value;
}

function fromBigInt(value: bigint): Uint8Array<ArrayBuffer> {
  const bytes = new Uint8Array(CODE_BYTES);
  for (let i = CODE_BYTES - 1; i >= 0; i--) {
    bytes[i] = Number(value & 0xffn);
    value >>= 8n;
  }
  return bytes;
}
