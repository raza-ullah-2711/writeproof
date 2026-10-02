package com.writeproof.backup;

import com.writeproof.common.Base64Url;
import com.writeproof.identity.Account;
import com.writeproof.identity.AccountRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stores and returns encrypted wallet backups. Uploading needs a login; fetching needs only the
 * lookup id, which is derived from the recovery code (128 bits), so it can't be guessed.
 */
@RestController
class WalletBackupController {

    record UploadRequest(@NotBlank String lookupId, @NotNull @Valid WalletBackupBlob blob) {}

    record BackupStatus(boolean backedUp, Instant backedUpAt) {}

    private final WalletBackupRepository backups;
    private final AccountRepository accounts;
    private final Clock clock;

    WalletBackupController(WalletBackupRepository backups, AccountRepository accounts, Clock clock) {
        this.backups = backups;
        this.accounts = accounts;
        this.clock = clock;
    }

    @PutMapping("/api/me/backup")
    BackupStatus upload(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UploadRequest request) {
        UUID accountId = UUID.fromString(jwt.getSubject());
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
        WalletBackupBlob blob = request.blob().validate();
        if (!Arrays.equals(Base64Url.decode(blob.publicKey()), account.publicKey())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Backup is not of this account's wallet");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!backups.upsert(accountId, lookupId(request.lookupId()), blob, now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Generate a new recovery code and try again");
        }
        return new BackupStatus(true, now);
    }

    @GetMapping("/api/me/backup")
    BackupStatus status(@AuthenticationPrincipal Jwt jwt) {
        return backups.findByAccount(UUID.fromString(jwt.getSubject()))
                .map(b -> new BackupStatus(true, b.createdAt()))
                .orElse(new BackupStatus(false, null));
    }

    @GetMapping("/api/backups/{lookupId}")
    WalletBackupBlob fetch(@PathVariable String lookupId) {
        return backups.findByLookupId(lookupId(lookupId))
                .map(WalletBackupRepository.StoredBackup::blob)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No backup for this recovery code"));
    }

    private static byte[] lookupId(String value) {
        byte[] id = Base64Url.decode(value);
        if (id.length != 32) {
            throw new IllegalArgumentException("lookupId must be 32 bytes");
        }
        return id;
    }
}
