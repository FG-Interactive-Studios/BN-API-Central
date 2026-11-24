package com.FGInteractive.BatalhaNaval.matchmaking.dto;

import java.util.List;
import com.FGInteractive.BatalhaNaval.match.mode.ModeSummary;

public record LobbyResponse(
    String code,
    String status,
    long revision,
    int capacity,
    List<LobbyPlayer> players,
    ModeSummary mode,
    boolean publicMatch,
    RematchStatus rematch,
    RivalryStatus rivalry
) {
    public record RivalryStatus(int hostWins, int guestWins, int draws) {}
    public record RematchStatus(boolean hostAccepted, boolean guestAccepted,
                                boolean available, boolean declined) {}
    public record LobbyPlayer(
        long id, String nickname, String avatarId, boolean ready, boolean connected,
        java.util.Map<String,String> cosmetics
    ) {}
}
