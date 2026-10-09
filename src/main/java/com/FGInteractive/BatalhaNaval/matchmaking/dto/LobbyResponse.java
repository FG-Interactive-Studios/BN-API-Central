package com.FGInteractive.BatalhaNaval.matchmaking.dto;

import java.util.List;

public record LobbyResponse(
    String code,
    String status,
    long revision,
    int capacity,
    List<LobbyPlayer> players
) {
    public record LobbyPlayer(
        long id, String nickname, String avatarId, boolean ready, boolean connected
    ) {}
}
