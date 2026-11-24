package com.FGInteractive.BatalhaNaval.match.controller;

import com.FGInteractive.BatalhaNaval.match.service.VirtualTauntService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** No user-supplied text, opponent ID, room code, or pack ID is accepted. */
@RestController
@RequestMapping("/api/matches/me/taunts")
public class VirtualTauntController {
    private final VirtualTauntService service;
    public VirtualTauntController(VirtualTauntService service) {this.service=service;}
    public record SendTauntRequest(@NotNull Integer index) {}
    @PostMapping
    public VirtualTauntService.TauntMessage send(@AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody SendTauntRequest request) {
        return service.send(Long.parseLong(jwt.getSubject()),request.index());
    }
}
