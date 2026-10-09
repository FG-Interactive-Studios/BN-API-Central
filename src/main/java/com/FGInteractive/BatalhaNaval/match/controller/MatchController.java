package com.FGInteractive.BatalhaNaval.match.controller;

import com.FGInteractive.BatalhaNaval.match.dto.PlaceFleetRequest;
import com.FGInteractive.BatalhaNaval.match.dto.PreparationResponse;
import com.FGInteractive.BatalhaNaval.match.service.MatchService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/matches/me/placement")
public class MatchController {
    private final MatchService matches;

    public MatchController(MatchService matches) { this.matches = matches; }

    @GetMapping
    public PreparationResponse mine(@AuthenticationPrincipal Jwt jwt) {
        return matches.mine(Long.parseLong(jwt.getSubject()));
    }

    @PutMapping
    public PreparationResponse place(@AuthenticationPrincipal Jwt jwt,
                                     @RequestBody PlaceFleetRequest request) {
        return matches.place(Long.parseLong(jwt.getSubject()), request);
    }

    @PostMapping("/random")
    public PreparationResponse random(@AuthenticationPrincipal Jwt jwt) {
        return matches.random(Long.parseLong(jwt.getSubject()));
    }

    @PostMapping("/confirm")
    public PreparationResponse confirm(@AuthenticationPrincipal Jwt jwt) {
        return matches.confirm(Long.parseLong(jwt.getSubject()));
    }
}
