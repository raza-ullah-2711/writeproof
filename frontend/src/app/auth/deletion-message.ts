/**
 * The exact bytes a wallet signs to delete its account; mirrors backend `DeletionMessage`. The
 * domain prefix keeps it from being valid as any other signature, and the timestamp keeps an old
 * request from being replayed.
 */
export const DELETION_DOMAIN = 'writeproof/delete-account/v1';

export function deletionMessage(accountId: string, requestedAt: string): Uint8Array<ArrayBuffer> {
  return new TextEncoder().encode(`${DELETION_DOMAIN}\n${accountId}\n${requestedAt}`);
}
