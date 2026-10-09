package com.FGInteractive.BatalhaNaval.shared.websocket;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RealtimeTicketController {
    private final RealtimeTicketService tickets;

    public RealtimeTicketController(RealtimeTicketService tickets) {
        this.tickets = tickets;
    }

    @PostMapping("/api/realtime/ticket")
    @ResponseStatus(HttpStatus.CREATED)
    public RealtimeTicketResponse issue(@AuthenticationPrincipal Jwt jwt) {
        return tickets.issue(jwt);
    }
}
