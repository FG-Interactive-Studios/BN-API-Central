package com.FGInteractive.BatalhaNaval.user.dto;

import java.time.Instant;

public record PlayerProfile(
    Long id, String nickname, String email, String avatarId, Instant createdAt
) {}
