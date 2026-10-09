package com.FGInteractive.BatalhaNaval.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(
    UUID id, Instant createdAt, Instant expiresAt, boolean current
) {}
