package com.FGInteractive.BatalhaNaval.matchmaking.controller;

import com.FGInteractive.BatalhaNaval.matchmaking.dto.FindMatchRequest;
import com.FGInteractive.BatalhaNaval.matchmaking.dto.QueueResponse;
import com.FGInteractive.BatalhaNaval.matchmaking.service.MatchmakingService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Public, code-free matchmaking. Private invitation routes remain unchanged. */
@RestController
@RequestMapping("/api/matchmaking/queue")
public class MatchmakingQueueController {
    private final MatchmakingService matchmaking;

    public MatchmakingQueueController(MatchmakingService matchmaking) {
        this.matchmaking = matchmaking;
    }

    @PostMapping
    public QueueResponse search(@AuthenticationPrincipal Jwt jwt,
                                @RequestBody(required = false) FindMatchRequest request) {
        return matchmaking.findOpponent(Long.parseLong(jwt.getSubject()),
            request == null ? null : request.modeId());
    }

    @GetMapping("/me")
    public QueueResponse mine(@AuthenticationPrincipal Jwt jwt) {
        return matchmaking.queueStatus(Long.parseLong(jwt.getSubject()));
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal Jwt jwt) {
        matchmaking.cancelSearch(Long.parseLong(jwt.getSubject()));
    }
}
