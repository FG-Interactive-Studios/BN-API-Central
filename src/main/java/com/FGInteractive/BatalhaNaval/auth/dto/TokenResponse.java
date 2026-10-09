package com.FGInteractive.BatalhaNaval.auth.dto;

import com.FGInteractive.BatalhaNaval.user.dto.PlayerProfile;

public record TokenResponse(
    String tokenType,
    String accessToken,
    long expiresIn,
    String refreshToken,
    PlayerProfile user
) {}
