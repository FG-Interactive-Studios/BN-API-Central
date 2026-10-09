package com.FGInteractive.BatalhaNaval.shared.websocket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class RealtimeHandshakeInterceptor implements HandshakeInterceptor {
    public static final String IDENTITY_ATTRIBUTE = "battleship.authenticatedIdentity";
    public static final String PROTOCOL = "battleship.v1";
    private static final String TICKET_PREFIX = "bn-ticket.";
    private final RealtimeTicketService tickets;

    public RealtimeHandshakeInterceptor(RealtimeTicketService tickets) {
        this.tickets = tickets;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // No credentials in query parameters, cookies, or client-supplied user IDs.
        if (request.getURI().getRawQuery() != null) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        List<String> protocols = new ArrayList<>();
        List<String> offered = request.getHeaders().get("Sec-WebSocket-Protocol");
        if (offered != null) {
            for (String value : offered) {
                Arrays.stream(value.split(",")).map(String::trim)
                    .filter(token -> !token.isEmpty()).forEach(protocols::add);
            }
        }
        if (protocols.size() != 2 || protocols.stream().filter(PROTOCOL::equals).count() != 1) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        String tokenProtocol = protocols.stream().filter(value -> value.startsWith(TICKET_PREFIX))
            .findFirst().orElse(null);
        if (tokenProtocol == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        RealtimeIdentity identity = tickets.consume(tokenProtocol.substring(TICKET_PREFIX.length()));
        if (identity == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(IDENTITY_ATTRIBUTE, identity);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // No state to restore: the ticket is already consumed.
    }
}
