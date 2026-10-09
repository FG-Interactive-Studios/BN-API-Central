package com.FGInteractive.BatalhaNaval.shared.websocket;

import com.FGInteractive.BatalhaNaval.auth.repository.AuthSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.handler.SubProtocolCapable;

@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable {
    private static final int MAX_PAYLOAD_CHARS = 4096;
    private static final CloseStatus SESSION_REVOKED = new CloseStatus(1008, "Session inactive");
    private final AuthSessionRepository sessions;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, Connection>> connections =
        new ConcurrentHashMap<>();

    public RealtimeWebSocketHandler(AuthSessionRepository sessions, ObjectMapper mapper) {
        this.sessions = sessions;
        this.mapper = mapper;
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of(RealtimeHandshakeInterceptor.PROTOCOL);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        RealtimeIdentity identity = identity(session);
        if (identity == null || !active(identity)) {
            session.close(SESSION_REVOKED);
            return;
        }

        WebSocketSession safeSocket = new ConcurrentWebSocketSessionDecorator(session, 5000, 65536);
        Connection connection = new Connection(identity, safeSocket);
        connections.computeIfAbsent(identity.userId(), ignored -> new ConcurrentHashMap<>())
            .put(session.getId(), connection);
        deliver(connection, "CONNECTED", Map.of());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        RealtimeIdentity identity = identity(session);
        if (identity == null || !active(identity)) {
            session.close(SESSION_REVOKED);
            return;
        }
        if (message.getPayloadLength() > MAX_PAYLOAD_CHARS) {
            session.close(CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }

        JsonNode parsed;
        try {
            parsed = mapper.readTree(message.getPayload());
        } catch (Exception ex) {
            send(session, "ERROR", Map.of("code", "INVALID_EVENT"));
            return;
        }
        if (parsed == null || !parsed.isObject() || parsed.size() != 1
            || !parsed.has("type") || !parsed.get("type").isTextual()) {
            send(session, "ERROR", Map.of("code", "INVALID_EVENT"));
            return;
        }

        if ("PING".equals(parsed.get("type").asText())) {
            send(session, "PONG", Map.of("serverTime", Instant.now().toString()));
        } else {
            // No gameplay or room commands are accepted until those domains enforce
            // membership server-side. A userId or roomId is never trusted from JSON.
            send(session, "ERROR", Map.of("code", "UNSUPPORTED_EVENT"));
        }
    }

    /**
     * To be invoked only by backend services after deciding game/lobby recipients.
     * Socket clients cannot call this method or choose recipient IDs.
     */
    public void sendToPlayer(long userId, String type, Object data) {
        Map<String, Connection> playerConnections = connections.get(userId);
        if (playerConnections == null) return;
        for (Connection connection : playerConnections.values()) {
            if (!active(connection.identity())) {
                terminate(connection, SESSION_REVOKED);
            } else {
                deliver(connection, type, data);
            }
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void closeRevokedSessions() {
        for (Map<String, Connection> playerConnections : connections.values()) {
            for (Connection connection : playerConnections.values()) {
                if (!active(connection.identity())) {
                    terminate(connection, SESSION_REVOKED);
                }
            }
        }
    }

    private boolean active(RealtimeIdentity identity) {
        return sessions.existsByIdAndUser_IdAndRevokedAtIsNullAndExpiresAtAfter(
            identity.authSessionId(), identity.userId(), Instant.now());
    }

    private RealtimeIdentity identity(WebSocketSession session) {
        Object value = session.getAttributes().get(RealtimeHandshakeInterceptor.IDENTITY_ATTRIBUTE);
        return value instanceof RealtimeIdentity principal ? principal : null;
    }

    private void send(WebSocketSession rawSession, String type, Object data) {
        RealtimeIdentity auth = identity(rawSession);
        if (auth == null) return;
        Map<String, Connection> current = connections.get(auth.userId());
        Connection connection = current == null ? null : current.get(rawSession.getId());
        if (connection != null) deliver(connection, type, data);
    }

    private void deliver(Connection connection, String type, Object data) {
        try {
            if (!connection.socket().isOpen()) {
                unregister(connection);
                return;
            }
            String json = mapper.writeValueAsString(new OutboundEvent(type, data));
            connection.socket().sendMessage(new TextMessage(json));
        } catch (Exception ex) {
            terminate(connection, CloseStatus.SERVER_ERROR);
        }
    }

    private void terminate(Connection connection, CloseStatus status) {
        unregister(connection);
        try { connection.socket().close(status); }
        catch (IOException ignored) { /* Socket is already closed. */ }
    }

    private void unregister(Connection connection) {
        long userId = connection.identity().userId();
        connections.computeIfPresent(userId, (id, sockets) -> {
            sockets.remove(connection.socket().getId(), connection);
            return sockets.isEmpty() ? null : sockets;
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        RealtimeIdentity principal = identity(session);
        if (principal != null) {
            Map<String, Connection> active = connections.get(principal.userId());
            if (active != null) {
                Connection connection = active.get(session.getId());
                if (connection != null) unregister(connection);
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        session.close(CloseStatus.SERVER_ERROR);
    }

    private record Connection(RealtimeIdentity identity, WebSocketSession socket) {}
    private record OutboundEvent(String type, Object data) {}
}
