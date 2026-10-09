package com.FGInteractive.BatalhaNaval.auth.dto;


import java.time.Instant;
public record RegisterResponse(Long id, String nickname, String email, Instant createdAt) {}
