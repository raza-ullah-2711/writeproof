package com.writeproof.identity;

import com.writeproof.common.Base64Url;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
class AccountController {

    record RegisterRequest(@NotBlank String publicKey) {}

    record AccountResponse(
            UUID accountId, String publicKey, Instant createdAt, String encryptionKey, String encryptionKeySignature) {
        static AccountResponse of(Account account) {
            return new AccountResponse(
                    account.id(),
                    Base64Url.encode(account.publicKey()),
                    account.createdAt(),
                    account.encryptionKey() == null ? null : Base64Url.encode(account.encryptionKey()),
                    account.encryptionKeySignature() == null ? null : Base64Url.encode(account.encryptionKeySignature()));
        }
    }

    record EncryptionKeyRequest(@NotBlank String encryptionKey, @NotBlank String signature) {}

    /** {@code signature}: the wallet's over {@link DeletionMessage} for this account and {@code requestedAt}. */
    record DeletionRequest(@NotBlank String requestedAt, @NotBlank String signature) {}

    /** {@code suspension} is null unless an admin suspended the account. */
    record StatusResponse(AccountStatus.Suspension suspension) {}

    private final AccountService accountService;
    private final AccountRepository accounts;
    private final AccountStatus status;
    private final AccountDeletionService deletion;

    AccountController(AccountService accountService, AccountRepository accounts, AccountStatus status,
                      AccountDeletionService deletion) {
        this.accountService = accountService;
        this.accounts = accounts;
        this.status = status;
        this.deletion = deletion;
    }

    @GetMapping("/me/status")
    StatusResponse status(@AuthenticationPrincipal Jwt jwt) {
        return new StatusResponse(status.of(UUID.fromString(jwt.getSubject())).suspension());
    }

    @PostMapping("/accounts")
    ResponseEntity<AccountResponse> register(@Valid @RequestBody RegisterRequest request) {
        Account account = accountService.register(Base64Url.decode(request.publicKey()));
        return ResponseEntity.created(URI.create("/api/me")).body(AccountResponse.of(account));
    }

    @PutMapping("/me/encryption-key")
    AccountResponse setEncryptionKey(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EncryptionKeyRequest request) {
        return AccountResponse.of(accountService.setEncryptionKey(
                UUID.fromString(jwt.getSubject()),
                Base64Url.decode(request.encryptionKey()),
                Base64Url.decode(request.signature())));
    }

    /** Looks up an account by its address (identity public key), e.g. to write it a letter. */
    @GetMapping("/accounts/by-key/{publicKey}")
    AccountResponse byKey(@PathVariable String publicKey) {
        return accounts.findByPublicKey(Base64Url.decode(publicKey))
                .map(a -> {
                    if (a.deleted()) {
                        throw AccountService.gone();
                    }
                    return AccountResponse.of(a);
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No account with this address"));
    }

    /** Deletes this account for good (docs/launch-policies.md, "Account deletion"). */
    @PostMapping("/me/deletion")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeletionRequest request) {
        deletion.delete(UUID.fromString(jwt.getSubject()), request.requestedAt(), Base64Url.decode(request.signature()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    AccountResponse me(@AuthenticationPrincipal Jwt jwt) {
        return accounts.findById(UUID.fromString(jwt.getSubject()))
                .map(AccountResponse::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
    }
}
