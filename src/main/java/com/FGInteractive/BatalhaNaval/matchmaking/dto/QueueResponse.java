package com.FGInteractive.BatalhaNaval.matchmaking.dto;

/** SEARCHING has no room or secret; MATCHED resumes the usual lobby workflow. */
public record QueueResponse(String status, String modeId, LobbyResponse room) {}
