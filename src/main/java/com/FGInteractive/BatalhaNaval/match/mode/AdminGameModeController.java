package com.FGInteractive.BatalhaNaval.match.mode;

import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Administrative control plane; permissions are checked against live DB state. */
@RestController
@RequestMapping("/api/admin/game-modes")
public class AdminGameModeController {
    private final GameModeAvailabilityService service;

    public AdminGameModeController(GameModeAvailabilityService service) {
        this.service = service;
    }

    @GetMapping
    public List<AdminGameModeResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.adminCatalog(Long.parseLong(jwt.getSubject()));
    }

    @PutMapping("/{modeId}/availability")
    public AdminGameModeResponse availability(
        @AuthenticationPrincipal Jwt jwt, @PathVariable String modeId,
        @RequestBody SetGameModeAvailabilityRequest request
    ) {
        return service.change(Long.parseLong(jwt.getSubject()), modeId,
            request == null ? null : request.enabled());
    }
}
