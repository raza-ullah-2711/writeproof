import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { Account } from '../auth/auth.service';
import { verifyEd25519 } from '../crypto/ed25519';
import { encryptionKeyBinding } from './letter-format';

/**
 * Looks up the account at `address` and returns its encryption key, but only if the account's
 * own identity key signed it: the server can't slip in a key of its own and read what is sealed.
 * Used for sealed letters and for sharing archive entries.
 */
export async function verifiedEncryptionKey(http: HttpClient, address: string): Promise<string> {
  const account = await firstValueFrom(
    http.get<Account>(`/api/accounts/by-key/${encodeURIComponent(address)}`),
  );
  if (account.publicKey !== address) {
    throw new Error('The server returned a different account than the address asked for');
  }
  if (!account.encryptionKey || !account.encryptionKeySignature) {
    throw new Error('This recipient has not set up sealed letters yet');
  }
  const binding = encryptionKeyBinding(account.publicKey, account.encryptionKey);
  if (!(await verifyEd25519(account.publicKey, binding, account.encryptionKeySignature))) {
    throw new Error("The recipient's encryption key is not signed by their identity key");
  }
  return account.encryptionKey;
}
