package com.FGInteractive.BatalhaNaval.matchmaking.controller;

import com.FGInteractive.BatalhaNaval.matchmaking.dto.JoinLobbyRequest;
import com.FGInteractive.BatalhaNaval.matchmaking.dto.CreateLobbyRequest;
import com.FGInteractive.BatalhaNaval.matchmaking.dto.LobbyResponse;
import com.FGInteractive.BatalhaNaval.matchmaking.dto.SetReadyRequest;
import com.FGInteractive.BatalhaNaval.matchmaking.service.MatchmakingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/matchmaking/lobbies")
public class MatchmakingController {
    private final MatchmakingService matchmaking;

    public MatchmakingController(MatchmakingService matchmaking) {
        this.matchmaking = matchmaking;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LobbyResponse create(@AuthenticationPrincipal Jwt jwt,
                                @RequestBody(required = false) CreateLobbyRequest request) {
        String modeId = request == null ? null : request.modeId();
        return matchmaking.create(Long.parseLong(jwt.getSubject()), modeId);
    }

    @PostMapping("/join")
    public LobbyResponse join(@AuthenticationPrincipal Jwt jwt,
                              @Valid @RequestBody JoinLobbyRequest request) {
        return matchmaking.join(Long.parseLong(jwt.getSubject()), request.code());
    }

    @GetMapping("/me")
    public LobbyResponse mine(@AuthenticationPrincipal Jwt jwt) {
        return matchmaking.mine(Long.parseLong(jwt.getSubject()));
    }

    @PutMapping("/me/ready")
    public LobbyResponse ready(@AuthenticationPrincipal Jwt jwt,
                               @Valid @RequestBody SetReadyRequest request) {
        return matchmaking.ready(Long.parseLong(jwt.getSubject()), request.ready());
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal Jwt jwt) {
        matchmaking.leave(Long.parseLong(jwt.getSubject()));
    }
}
