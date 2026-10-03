package com.writeproof.identity;

import com.writeproof.system.SystemSettings;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final Clock clock;
    private final SystemSettings settings;

    AccountService(AccountRepository accounts, Clock clock, SystemSettings settings) {
        this.settings = settings;
        this.accounts = accounts;
        this.clock = clock;
    }

    /** 410 for a deleted account: it can't sign in, register again or receive letters. */
    public static ResponseStatusException gone() {
        return new ResponseStatusException(HttpStatus.GONE,
                "This account was deleted. To use Writeproof again, create a new wallet.");
    }

    /** Registers a wallet's public key as a new account. */
    public Account register(byte[] publicKey) {
        settings.requireRegistrationOpen();
        Ed25519.decodePublicKey(publicKey); // rejects malformed keys with IllegalArgumentException
        Account account = new Account(
                UUID.randomUUID(), publicKey, clock.instant().truncatedTo(ChronoUnit.MICROS), null, null, null);
        if (!accounts.insertIfAbsent(account)) {
            // A deleted wallet can't come back as if it were the same person with no history.
            if (accounts.findByPublicKey(publicKey).map(Account::deleted).orElse(false)) {
                throw gone();
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Public key is already registered");
        }
        return account;
    }

    /**
     * Registers the account's X25519 encryption key. The identity key must have signed the
     * binding, so nobody (including this server) can attach a key the owner doesn't hold.
     * Setting the same key again is a no-op; replacing it is not supported yet.
     */
    public Account setEncryptionKey(UUID accountId, byte[] encryptionKey, byte[] signature) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
        if (encryptionKey.length != 32) {
            throw new IllegalArgumentException("X25519 public key must be 32 bytes");
        }
        if (!Ed25519.verify(account.publicKey(), EncryptionKeyBinding.of(account.publicKey(), encryptionKey), signature)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Encryption key is not signed by this account");
        }
        if (account.canReceiveLetters()) {
            if (java.util.Arrays.equals(account.encryptionKey(), encryptionKey)) {
                return account;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An encryption key is already registered");
        }
        if (!accounts.setEncryptionKeyIfAbsent(accountId, encryptionKey, signature)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An encryption key is already registered");
        }
        return accounts.findById(accountId).orElseThrow();
    }
}
