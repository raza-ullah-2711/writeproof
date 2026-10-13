import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Account } from '../auth/auth.service';
import { toBase64Url } from '../crypto/base64url';
import { verifyEd25519 } from '../crypto/ed25519';
import { WalletService } from '../wallet/wallet.service';
import { LedgerVerifier } from './ledger-verifier';
import { verifiedEncryptionKey } from './verified-account';
import { LedgerEntry } from './ledger-verify';
import { HandwritingSample, parseSample, toJson } from '../handwriting/handwriting-sample';
import { LetterPlaintext, openLetter, sealLetter } from './letter-crypto';
import { Liveness, checkLiveness } from '../pages/liveness';
import {
  MAX_PAGES,
  MAX_PAGE_POINTS,
  Page,
  inkHash,
  pagePointCount,
  pageStrokes,
} from '../pages/page-format';
import {
  LetterEnvelope,
  encryptionKeyBinding,
  handwritingHash,
  letterHash,
  letterHeader,
  letterHeaderV2,
  letterHeaderV3,
  letterHeaderV4,
  letterSignedMessage,
} from './letter-format';

export interface Party {
  accountId: string;
  publicKey: string;
  /** The account was deleted: its side of past letters can't be opened, and it can't be written to. */
  deleted?: boolean;
}

export interface Letter {
  letterId: string;
  sender: Party;
  recipient: Party;
  sentAt: string;
  envelope: LetterEnvelope;
  signature: string;
  letterHash: string;
  ledger: LedgerEntry;
  /** Null for letters sent before hand-signing (v1). */
  handwritingHash: string | null;
  /** Similarity the server measured when the letter was sent (v2 only). */
  handwritingScore: number | null;
  /** For a reply (v3): the hash of the letter it answers. Signed, so checked on opening. */
  inReplyTo: string | null;
  /** Hash of the letter that started the conversation (its own hash, if it did). */
  threadId: string;
  /** Ink hashes of the handwritten pages sealed inside (v4); null or absent for other letters. */
  pageInkHashes?: string[] | null;
}

/** A conversation, as listed by the server. */
export interface ThreadSummary {
  threadId: string;
  counterpart: Party;
  letters: number;
  latestSeq: number;
  latestSentAt: string;
}

/** Whether a conversation's letters link up; null if they do, otherwise what's wrong. */
export function checkThread(threadId: string, letters: readonly Letter[]): string | null {
  if (letters.length === 0) {
    return 'The conversation is empty';
  }
  const [first, ...rest] = letters;
  if (first.letterHash !== threadId || first.inReplyTo !== null) {
    return "The conversation doesn't start with the letter it is named after";
  }
  const pair = [first.sender.publicKey, first.recipient.publicKey].sort().join();
  const seen = new Set([first.letterHash]);
  for (const letter of rest) {
    if ([letter.sender.publicKey, letter.recipient.publicKey].sort().join() !== pair) {
      return 'A letter in this conversation is between different people';
    }
    if (letter.threadId !== threadId || !letter.inReplyTo || !seen.has(letter.inReplyTo)) {
      return `Letter #${letter.ledger.seq} doesn't answer an earlier letter in this conversation`;
    }
    seen.add(letter.letterHash);
  }
  return null;
}

/** What the reader's own browser established about a letter. Nothing here is taken on trust. */
export interface OpenedLetter {
  body: string | null;
  /**
   * The sealed signature hashes to what the sender's wallet signed, so these are exactly the
   * strokes the server verified when the letter was sent. Null for v1 letters (no hand signature).
   */
  handSigned: boolean | null;
  handwriting: HandwritingSample | null;
  /** Hash recomputed from the envelope matches, and the sender's key signed it. */
  signatureValid: boolean;
  /** Decrypted with this wallet's key (so it was sealed for us and not altered). */
  decrypted: boolean;
  /**
   * The letter's ledger entry commits to its hash, and is provably in a checkpoint signed by the
   * ledger key this browser pinned, which provably extends every checkpoint it saw before.
   */
  ledgerValid: boolean;
  ledgerProblem: string | null;
  /** Size of the signed checkpoint the entry was proven against, when the proof held. */
  ledgerCheckpointSize: number | null;
  /** Handwritten pages sealed inside (v4), with their timing; null for other letters. */
  pages: Page[] | null;
  /**
   * Checked by this browser (the server never sees the pages): each page hashes to the ink the
   * sender's wallet signed, was written within 12 hours before sending, and looks written live.
   * Null for letters without pages.
   */
  pageChecks: PageCheck[] | null;
}

/** What this browser found about one sealed page. */
export interface PageCheck {
  /** Hashes to the ink hash in the signed header. */
  matches: boolean;
  /** First pen-down no more than 12 hours before the letter was sent (nor after it). */
  fresh: boolean;
  /** The same liveness heuristics the server runs on open letters' pages. */
  liveness: Liveness;
}

/** How long before sending a page may have been written. */
const MAX_PAGE_AGE_MS = 12 * 60 * 60 * 1000;

export const MAX_BODY_LENGTH = 10_000;

@Injectable({ providedIn: 'root' })
export class LettersService {
  private readonly http = inject(HttpClient);
  private readonly wallet = inject(WalletService);
  private readonly ledger = inject(LedgerVerifier);

  /**
   * Seals, signs (by wallet and by hand) and sends a letter. The handwritten signature travels
   * sealed inside the letter; its hash is in the signed header. The recipient's encryption key
   * is only used if their identity key signed it, so the server can't slip in a key of its own.
   */
  async send(
    recipientAddress: string,
    body: string,
    signature: HandwritingSample,
    inReplyTo?: Letter,
    pages: Page[] = [],
  ): Promise<Letter> {
    const me = this.wallet.publicKey();
    const myEncryptionKey = this.wallet.encryptionPublicKey();
    if (!me || !myEncryptionKey) {
      throw new Error('No wallet is loaded');
    }
    if (body.length > MAX_BODY_LENGTH || (pages.length === 0 && body.trim().length === 0)) {
      throw new Error(`A letter needs 1 to ${MAX_BODY_LENGTH} characters`);
    }
    if (pages.length > MAX_PAGES) {
      throw new Error(`A letter can have at most ${MAX_PAGES} pages`);
    }
    if (pages.some((p) => pagePointCount(p) > MAX_PAGE_POINTS)) {
      throw new Error('A page has too much writing; split it over two pages');
    }
    if (inReplyTo) {
      // A conversation is between two people: the reply goes to whoever else is in it.
      const parties = [inReplyTo.sender.publicKey, inReplyTo.recipient.publicKey];
      const other = parties.find((p) => p !== me) ?? me;
      if (!parties.includes(me) || recipientAddress !== other) {
        throw new Error('A reply goes to the other person in the conversation');
      }
    }
    const recipientEncryptionKey = await verifiedEncryptionKey(this.http, recipientAddress);

    const handwriting = toJson(signature);
    const sentAt = new Date().toISOString();
    const inkHashes = await Promise.all(pages.map((p) => inkHash(p)));
    const sealed = await sealLetter({
      ...(pages.length > 0 ? { pages: { pages, inkHashes } } : {}),
      handwriting,
      body,
      senderKey: me,
      recipientKey: recipientAddress,
      senderEncryptionKey: myEncryptionKey,
      recipientEncryptionKey,
      sentAt,
      inReplyTo: inReplyTo?.letterHash,
    });
    const walletSignature = await this.wallet.sign(letterSignedMessage(sealed.hash));
    const letter = await firstValueFrom(
      this.http.post<Letter>('/api/letters', {
        recipientPublicKey: recipientAddress,
        sentAt,
        envelope: sealed.envelope,
        signature: toBase64Url(walletSignature),
        handwriting,
        ...(inReplyTo ? { inReplyTo: inReplyTo.letterHash } : {}),
        ...(pages.length > 0 ? { pageInkHashes: inkHashes } : {}),
      }),
    );
    if (letter.letterHash !== toBase64Url(sealed.hash)) {
      throw new Error('The server recorded a different letter hash');
    }
    return letter;
  }

  inbox(): Promise<Letter[]> {
    return firstValueFrom(this.http.get<Letter[]>('/api/letters/inbox'));
  }

  sent(): Promise<Letter[]> {
    return firstValueFrom(this.http.get<Letter[]>('/api/letters/sent'));
  }

  threads(): Promise<ThreadSummary[]> {
    return firstValueFrom(this.http.get<ThreadSummary[]>('/api/letters/threads'));
  }

  /** A conversation's letters, oldest first. */
  thread(threadId: string): Promise<Letter[]> {
    return firstValueFrom(
      this.http.get<Letter[]>(`/api/letters/threads/${encodeURIComponent(threadId)}`),
    );
  }

  /** Decrypts and independently verifies a letter: signature, sealing and ledger proofs. */
  async open(letter: Letter): Promise<OpenedLetter> {
    const me = this.wallet.publicKey();
    const myEncryptionKey = this.wallet.encryptionPublicKey();
    if (!me || !myEncryptionKey) {
      throw new Error('No wallet is loaded');
    }
    const header = headerOf(letter);
    const hash = await letterHash(header, letter.envelope);
    const hashMatches = toBase64Url(hash) === letter.letterHash;
    const signatureValid =
      hashMatches &&
      (await verifyEd25519(letter.sender.publicKey, letterSignedMessage(hash), letter.signature));

    let plaintext: LetterPlaintext | null = null;
    try {
      const role = letter.recipient.publicKey === me ? 'recipient' : 'sender';
      plaintext = await openLetter(letter.envelope, header, role, myEncryptionKey, (k) =>
        this.wallet.ecdh(k),
      );
    } catch {
      plaintext = null;
    }

    let handSigned: boolean | null = null;
    let handwriting: HandwritingSample | null = null;
    if (letter.handwritingHash) {
      const sealed = plaintext?.handwriting;
      handSigned =
        signatureValid &&
        sealed !== undefined &&
        (await handwritingHash(sealed)) === letter.handwritingHash;
      try {
        handwriting = handSigned && sealed ? parseSample(sealed) : null;
      } catch {
        handSigned = false;
      }
    }

    const pages = letter.pageInkHashes?.length ? (plaintext?.pages ?? null) : null;
    const pageChecks = letter.pageInkHashes?.length
      ? await checkPages(pages ?? [], letter.pageInkHashes, letter.sentAt, signatureValid)
      : null;
    const ledger = await this.ledger.verifyEntry(letter.ledger, toBase64Url(hash));
    return {
      pages,
      pageChecks,
      body: plaintext?.body ?? null,
      handSigned,
      handwriting,
      signatureValid,
      decrypted: plaintext !== null,
      ledgerValid: ledger.problem === null,
      ledgerProblem: ledger.problem,
      ledgerCheckpointSize: ledger.problem === null ? ledger.size : null,
    };
  }
}

/**
 * Checks sealed pages in this browser: one per signed ink hash, in order, each matching its hash,
 * written shortly before sending, and passing the liveness heuristics.
 */
async function checkPages(
  pages: Page[],
  signedInkHashes: readonly string[],
  sentAt: string,
  signatureValid: boolean,
): Promise<PageCheck[]> {
  const sent = Date.parse(sentAt);
  return Promise.all(
    signedInkHashes.map(async (signed, i) => {
      const page = pages[i];
      if (!page || !page.timing || !page.capturedAt) {
        return { matches: false, fresh: false, liveness: checkLiveness([], 'mouse') };
      }
      const captured = Date.parse(page.capturedAt);
      return {
        matches:
          signatureValid &&
          pages.length === signedInkHashes.length &&
          (await inkHash(page)) === signed,
        fresh: captured >= sent - MAX_PAGE_AGE_MS && captured <= sent + 2 * 60 * 1000,
        liveness: checkLiveness(pageStrokes(page), page.device),
      };
    }),
  );
}

/** The signed header a letter claims: v4 with pages, v3 for replies, v2 hand-signed, v1 before. */
function headerOf(letter: Letter): string {
  const { sender, recipient, sentAt, handwritingHash: hw, inReplyTo } = letter;
  if (!hw) {
    return letterHeader(sender.publicKey, recipient.publicKey, sentAt);
  }
  if (letter.pageInkHashes?.length) {
    return letterHeaderV4(
      sender.publicKey,
      recipient.publicKey,
      sentAt,
      hw,
      inReplyTo ?? null,
      letter.pageInkHashes,
    );
  }
  return inReplyTo
    ? letterHeaderV3(sender.publicKey, recipient.publicKey, sentAt, hw, inReplyTo)
    : letterHeaderV2(sender.publicKey, recipient.publicKey, sentAt, hw);
}
