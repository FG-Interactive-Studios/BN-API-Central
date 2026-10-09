package com.FGInteractive.BatalhaNaval.user.dto;

import java.time.Instant;
public record PublicPlayerProfile(Long id, String nickname, String avatarId, Instant createdAt) {}
