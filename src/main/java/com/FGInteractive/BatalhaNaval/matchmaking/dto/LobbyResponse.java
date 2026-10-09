package com.FGInteractive.BatalhaNaval.matchmaking.dto;

import java.util.List;
import com.FGInteractive.BatalhaNaval.match.mode.ModeSummary;

public record LobbyResponse(
    String code,
    String status,
    long revision,
    int capacity,
    List<LobbyPlayer> players,
    ModeSummary mode
) {
    public record LobbyPlayer(
        long id, String nickname, String avatarId, boolean ready, boolean connected
    ) {}
}
