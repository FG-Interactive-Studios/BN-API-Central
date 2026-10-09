package com.FGInteractive.BatalhaNaval.match.controller;

import com.FGInteractive.BatalhaNaval.match.dto.BattleResponse;
import com.FGInteractive.BatalhaNaval.match.dto.FireRequest;
import com.FGInteractive.BatalhaNaval.match.service.BattleService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Game actions are scoped to the authenticated player's sole active lobby. */
@RestController
@RequestMapping("/api/matches/me")
public class BattleController {
    private final BattleService battles;
    public BattleController(BattleService battles) { this.battles = battles; }

    @GetMapping
    public BattleResponse mine(@AuthenticationPrincipal Jwt jwt) {
        return battles.mine(Long.parseLong(jwt.getSubject()));
    }

    @PostMapping("/forfeit")
    public BattleResponse forfeit(@AuthenticationPrincipal Jwt jwt) {
        return battles.forfeit(Long.parseLong(jwt.getSubject()));
    }

    @PostMapping("/shots")
    public BattleResponse fire(@AuthenticationPrincipal Jwt jwt,
                               @RequestBody FireRequest request) {
        return battles.fire(Long.parseLong(jwt.getSubject()), request);
    }
}
