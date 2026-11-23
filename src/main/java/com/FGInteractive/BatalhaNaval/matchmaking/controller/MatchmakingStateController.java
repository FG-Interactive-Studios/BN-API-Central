package com.FGInteractive.BatalhaNaval.matchmaking.controller;

import com.FGInteractive.BatalhaNaval.matchmaking.dto.QueueResponse;
import com.FGInteractive.BatalhaNaval.matchmaking.service.MatchmakingService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Safe authenticated matchmaking snapshot:
 * 200 IDLE (no lobby or queue), SEARCHING or MATCHED (public/private room).
 * Legacy /lobbies/me and /queue/me retain their existing 404 semantics.
 */
@RestController
public class MatchmakingStateController {
    private final MatchmakingService matchmaking;

    public MatchmakingStateController(MatchmakingService matchmaking) {
        this.matchmaking = matchmaking;
    }

    @GetMapping("/api/matchmaking/state")
    public QueueResponse state(@AuthenticationPrincipal Jwt jwt) {
        return matchmaking.matchmakingState(Long.parseLong(jwt.getSubject()));
    }
}
