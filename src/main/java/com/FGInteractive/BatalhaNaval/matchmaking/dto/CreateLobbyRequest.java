package com.FGInteractive.BatalhaNaval.matchmaking.dto;

/** Optional at creation; omitting modeId preserves classic for existing clients. */
public record CreateLobbyRequest(String modeId) {}
