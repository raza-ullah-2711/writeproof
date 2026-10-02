package com.writeproof.identity;

import java.time.Instant;
import java.util.UUID;

public record Account(UUID id, byte[] publicKey, Instant createdAt) {}
