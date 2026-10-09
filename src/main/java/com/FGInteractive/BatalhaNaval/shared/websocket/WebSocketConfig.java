package com.FGInteractive.BatalhaNaval.shared.websocket;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@EnableScheduling
public class WebSocketConfig implements WebSocketConfigurer {
    private final RealtimeWebSocketHandler handler;
    private final RealtimeHandshakeInterceptor handshake;
    private final String origins;

    public WebSocketConfig(RealtimeWebSocketHandler handler,
                           RealtimeHandshakeInterceptor handshake,
                           @Value("${realtime.allowed-origins:}") String origins) {
        this.handler = handler;
        this.handshake = handshake;
        this.origins = origins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var registration = registry.addHandler(handler, "/ws").addInterceptors(handshake);
        if (!origins.isBlank()) {
            String[] allowed = Arrays.stream(origins.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toArray(String[]::new);
            if (Arrays.asList(allowed).contains("*")) {
                throw new IllegalStateException("Realtime allowed origins must not contain wildcard '*'");
            }
            registration.setAllowedOrigins(allowed);
        }
        // Default Spring WebSocket origin policy: same origin only.
    }
}
