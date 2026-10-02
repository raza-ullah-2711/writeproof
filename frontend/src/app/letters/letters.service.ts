import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Account } from '../auth/auth.service';
import { toBase64Url } from '../crypto/base64url';
import { verifyEd25519 } from '../crypto/ed25519';
import { WalletService } from '../wallet/wallet.service';
import { LedgerEntry, verifyChain } from './ledger-verify';
import { openLetter, sealLetter } from './letter-crypto';
import {
  LetterEnvelope,
  encryptionKeyBinding,
  letterHash,
  letterHeader,
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
}

/** What the reader's own browser established about a letter. Nothing here is taken on trust. */
export interface OpenedLetter {
  body: string | null;
  /** Hash recomputed from the envelope matches, and the sender's key signed it. */
  signatureValid: boolean;
  /** Decrypted with this wallet's key (so it was sealed for us and not altered). */
  decrypted: boolean;
  /** Chain re-walked from genesis to this letter's entry, which commits to this letter's hash. */
  ledgerValid: boolean;
  ledgerProblem: string | null;
}

export const MAX_BODY_LENGTH = 10_000;
const LEDGER_PAGE = 1000;

@Injectable({ providedIn: 'root' })
export class LettersService {
  private readonly http = inject(HttpClient);
  private readonly wallet = inject(WalletService);

  /**
   * Seals, signs and sends a letter. The recipient's encryption key is only used if their
   * identity key signed it, so the server can't slip in a key of its own.
   */
  async send(recipientAddress: string, body: string): Promise<Letter> {
    const me = this.wallet.publicKey();
    const myEncryptionKey = this.wallet.encryptionPublicKey();
    if (!me || !myEncryptionKey) {
      throw new Error('No wallet is loaded');
    }
    if (body.trim().length === 0 || body.length > MAX_BODY_LENGTH) {
      throw new Error(`A letter needs 1 to ${MAX_BODY_LENGTH} characters`);
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

    const sentAt = new Date().toISOString();
    const sealed = await sealLetter({
      body,
      senderKey: me,
      recipientKey: recipient.publicKey,
      senderEncryptionKey: myEncryptionKey,
      recipientEncryptionKey: recipient.encryptionKey,
      sentAt,
    });
    const signature = await this.wallet.sign(letterSignedMessage(sealed.hash));
    const letter = await firstValueFrom(
      this.http.post<Letter>('/api/letters', {
        recipientPublicKey: recipient.publicKey,
        sentAt,
        envelope: sealed.envelope,
        signature: toBase64Url(signature),
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

  /** Decrypts and independently verifies a letter: signature, sealing and ledger chain. */
  async open(letter: Letter): Promise<OpenedLetter> {
    const me = this.wallet.publicKey();
    const myEncryptionKey = this.wallet.encryptionPublicKey();
    if (!me || !myEncryptionKey) {
      throw new Error('No wallet is loaded');
    }
    const header = letterHeader(letter.sender.publicKey, letter.recipient.publicKey, letter.sentAt);
    const hash = await letterHash(header, letter.envelope);
    const hashMatches = toBase64Url(hash) === letter.letterHash;
    const signatureValid =
      hashMatches &&
      (await verifyEd25519(letter.sender.publicKey, letterSignedMessage(hash), letter.signature));

    let body: string | null = null;
    try {
      const role = letter.recipient.publicKey === me ? 'recipient' : 'sender';
      body = (
        await openLetter(letter.envelope, header, role, myEncryptionKey, (k) => this.wallet.ecdh(k))
      ).body;
    } catch {
      body = null;
    }

    const ledgerProblem = await this.checkLedger(letter, toBase64Url(hash));
    return {
      body,
      signatureValid,
      decrypted: body !== null,
      ledgerValid: ledgerProblem === null,
      ledgerProblem,
    };
  }

  /** Returns null if the letter is provably in an intact chain, otherwise what's wrong. */
  private async checkLedger(letter: Letter, hash: string): Promise<string | null> {
    const entries: LedgerEntry[] = [];
    while (entries.length < letter.ledger.seq) {
      const page = await firstValueFrom(
        this.http.get<LedgerEntry[]>('/api/ledger/entries', {
          params: { from: entries.length + 1, limit: LEDGER_PAGE },
        }),
      );
      if (page.length === 0) {
        break;
      }
      entries.push(...page);
    }
    const upToLetter = entries.slice(0, letter.ledger.seq);
    const chain = await verifyChain(upToLetter);
    if (!chain.intact) {
      return `Chain broken at entry ${chain.brokenAt}: ${chain.reason}`;
    }
    const entry = upToLetter.at(-1);
    if (!entry || entry.seq !== letter.ledger.seq) {
      return 'The ledger has no entry for this letter';
    }
    if (entry.payloadHash !== hash || entry.entryHash !== letter.ledger.entryHash) {
      return "The ledger entry doesn't commit to this letter";
    }
    return null;
  }
}
