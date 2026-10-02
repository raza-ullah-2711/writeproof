package com.writeproof.identity;

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

    AccountService(AccountRepository accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    /** Registers a wallet's public key as a new account. */
    public Account register(byte[] publicKey) {
        Ed25519.decodePublicKey(publicKey); // rejects malformed keys with IllegalArgumentException
        Account account = new Account(UUID.randomUUID(), publicKey, clock.instant().truncatedTo(ChronoUnit.MICROS));
        if (!accounts.insertIfAbsent(account)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Public key is already registered");
        }
        return account;
    }
}
