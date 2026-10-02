import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Account } from '../auth/auth.service';
import { toBase64Url } from '../crypto/base64url';
import { verifyEd25519 } from '../crypto/ed25519';
import { WalletService } from '../wallet/wallet.service';
import { LedgerVerifier } from './ledger-verifier';
import { LedgerEntry } from './ledger-verify';
import { HandwritingSample, parseSample, toJson } from '../handwriting/handwriting-sample';
import { LetterPlaintext, openLetter, sealLetter } from './letter-crypto';
import {
  LetterEnvelope,
  encryptionKeyBinding,
  handwritingHash,
  letterHash,
  letterHeader,
  letterHeaderV2,
  letterHeaderV3,
  letterSignedMessage,
} from './letter-format';

export interface Party {
  accountId: string;
  publicKey: string;
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
}

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
  ): Promise<Letter> {
    const me = this.wallet.publicKey();
    const myEncryptionKey = this.wallet.encryptionPublicKey();
    if (!me || !myEncryptionKey) {
      throw new Error('No wallet is loaded');
    }
    if (body.trim().length === 0 || body.length > MAX_BODY_LENGTH) {
      throw new Error(`A letter needs 1 to ${MAX_BODY_LENGTH} characters`);
    }
    if (inReplyTo) {
      // A conversation is between two people: the reply goes to whoever else is in it.
      const parties = [inReplyTo.sender.publicKey, inReplyTo.recipient.publicKey];
      const other = parties.find((p) => p !== me) ?? me;
      if (!parties.includes(me) || recipientAddress !== other) {
        throw new Error('A reply goes to the other person in the conversation');
      }
    }
    const recipient = await firstValueFrom(
      this.http.get<Account>(`/api/accounts/by-key/${encodeURIComponent(recipientAddress)}`),
    );
    if (recipient.publicKey !== recipientAddress) {
      throw new Error('The server returned a different account than the address asked for');
    }
    if (!recipient.encryptionKey || !recipient.encryptionKeySignature) {
      throw new Error('This recipient has not set up sealed letters yet');
    }
    const binding = encryptionKeyBinding(recipient.publicKey, recipient.encryptionKey);
    if (!(await verifyEd25519(recipient.publicKey, binding, recipient.encryptionKeySignature))) {
      throw new Error("The recipient's encryption key is not signed by their identity key");
    }

    const handwriting = toJson(signature);
    const sentAt = new Date().toISOString();
    const sealed = await sealLetter({
      handwriting,
      body,
      senderKey: me,
      recipientKey: recipient.publicKey,
      senderEncryptionKey: myEncryptionKey,
      recipientEncryptionKey: recipient.encryptionKey,
      sentAt,
      inReplyTo: inReplyTo?.letterHash,
    });
    const walletSignature = await this.wallet.sign(letterSignedMessage(sealed.hash));
    const letter = await firstValueFrom(
      this.http.post<Letter>('/api/letters', {
        recipientPublicKey: recipient.publicKey,
        sentAt,
        envelope: sealed.envelope,
        signature: toBase64Url(walletSignature),
        handwriting,
        ...(inReplyTo ? { inReplyTo: inReplyTo.letterHash } : {}),
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

    const ledger = await this.ledger.verifyEntry(letter.ledger, toBase64Url(hash));
    return {
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

/** The signed header a letter claims: v3 for replies, v2 for hand-signed, v1 from before. */
function headerOf(letter: Letter): string {
  const { sender, recipient, sentAt, handwritingHash: hw, inReplyTo } = letter;
  if (!hw) {
    return letterHeader(sender.publicKey, recipient.publicKey, sentAt);
  }
  return inReplyTo
    ? letterHeaderV3(sender.publicKey, recipient.publicKey, sentAt, hw, inReplyTo)
    : letterHeaderV2(sender.publicKey, recipient.publicKey, sentAt, hw);
}
