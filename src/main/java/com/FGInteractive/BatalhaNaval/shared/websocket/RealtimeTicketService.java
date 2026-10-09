package com.FGInteractive.BatalhaNaval.shared.websocket;

import com.FGInteractive.BatalhaNaval.auth.repository.AuthSessionRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RealtimeTicketService {
    private static final Duration TICKET_LIFETIME = Duration.ofSeconds(30);
    private static final int MAX_PENDING_TICKETS = 2000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ConcurrentHashMap<String, PendingTicket> pending = new ConcurrentHashMap<>();
    private final AuthSessionRepository sessions;

    public RealtimeTicketService(AuthSessionRepository sessions) {
        this.sessions = sessions;
    }

    public RealtimeTicketResponse issue(Jwt jwt) {
        long userId;
        UUID sessionId;
        try {
            userId = Long.parseLong(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getClaimAsString("sid"));
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authentication");
        }

        if (!sessions.existsByIdAndUser_IdAndRevokedAtIsNullAndExpiresAtAfter(
            sessionId, userId, Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session");
        }

        Instant now = Instant.now();
        pending.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        if (pending.size() >= MAX_PENDING_TICKETS) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Realtime capacity exceeded");
        }

        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant expiresAt = now.plus(TICKET_LIFETIME);
        pending.put(ticket, new PendingTicket(new RealtimeIdentity(userId, sessionId), expiresAt));
        return new RealtimeTicketResponse(ticket, expiresAt);
    }

    /**
     * Atomic single-use consumption. Even an unsuccessful handshake cannot replay this ticket.
     */
    public RealtimeIdentity consume(String ticket) {
        if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{43}")) {
            return null;
        }
        PendingTicket record = pending.remove(ticket);
        if (record == null || !record.expiresAt().isAfter(Instant.now())) {
            return null;
        }
        RealtimeIdentity identity = record.identity();
        if (!sessions.existsByIdAndUser_IdAndRevokedAtIsNullAndExpiresAtAfter(
            identity.authSessionId(), identity.userId(), Instant.now())) {
            return null;
        }
        return identity;
    }

    private record PendingTicket(RealtimeIdentity identity, Instant expiresAt) {}
}
