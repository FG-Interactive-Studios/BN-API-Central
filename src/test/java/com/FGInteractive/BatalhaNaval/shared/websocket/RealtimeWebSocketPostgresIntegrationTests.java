package com.FGInteractive.BatalhaNaval.shared.websocket;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("ci")
class RealtimeWebSocketPostgresIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired RealtimeWebSocketHandler sockets;
    @Autowired Environment environment;

    private String createAndLogin() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "realtime-" + suffix + "@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"nickname":"Socket%s","email":"%s","password":"safe-password-123"}
                """.formatted(suffix, email))).andExpect(status().isCreated());
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"safe-password-123"}
                """.formatted(email))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private String ticket(String jwt) throws Exception {
        String json = mvc.perform(post("/api/realtime/ticket")
            .header("Authorization", "Bearer " + jwt))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.ticket").isNotEmpty())
            .andExpect(jsonPath("$.expiresAt").exists())
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.ticket");
    }

    private URI uri(String suffix) {
        return URI.create("ws://localhost:" + environment.getRequiredProperty("local.server.port") + "/ws" + suffix);
    }

    private Connection connect(String ticket) throws Exception {
        Listener listener = new Listener();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .subprotocols("battleship.v1", "bn-ticket." + ticket)
            .buildAsync(uri(""), listener).join();
        assertEquals("battleship.v1", socket.getSubprotocol());
        assertTrue(listener.next().contains("\"type\":\"CONNECTED\""));
        return new Connection(socket, listener);
    }

    @Test
    void deniesAnonymousAccessAndReplayAndQueryTickets() throws Exception {
        mvc.perform(post("/api/realtime/ticket")).andExpect(status().isUnauthorized());
        String jwt = JsonPath.read(createAndLogin(), "$.accessToken");
        String ticketA = ticket(jwt);
        assertThrows(CompletionException.class, () ->
            HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .subprotocols("battleship.v1")
                .buildAsync(uri(""), new Listener()).join());
        try (Connection first = connect(ticketA)) {
            assertThrows(CompletionException.class, () ->
                HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .subprotocols("battleship.v1", "bn-ticket." + ticketA)
                    .buildAsync(uri(""), new Listener()).join());
        }
        String secondTicket = ticket(jwt);
        assertThrows(CompletionException.class, () ->
            HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .subprotocols("battleship.v1", "bn-ticket." + secondTicket)
                .buildAsync(uri("?userId=123"), new Listener()).join());
    }

    @Test
    void privateEventsNeverReachOtherPlayers() throws Exception {
        String first = createAndLogin(), second = createAndLogin();
        long idA = ((Number) JsonPath.read(first, "$.user.id")).longValue();
        try (Connection a = connect(ticket(JsonPath.read(first, "$.accessToken")));
             Connection b = connect(ticket(JsonPath.read(second, "$.accessToken")))) {
            a.socket.sendText("{\"type\":\"PING\"}", true).join();
            assertTrue(a.listener.next().contains("\"type\":\"PONG\""));
            assertNull(b.listener.noMessage());

            a.socket.sendText("{\"type\":\"PING\",\"userId\":99999}", true).join();
            assertTrue(a.listener.next().contains("\"code\":\"INVALID_EVENT\""));

            sockets.sendToPlayer(idA, "SERVER_NOTIFICATION", Map.of("value", "private"));
            assertTrue(a.listener.next().contains("\"type\":\"SERVER_NOTIFICATION\""));
            assertNull(b.listener.noMessage());
        }
    }

    @Test
    void revocationTerminatesSocketAndUnconsumedTicket() throws Exception {
        String login = createAndLogin();
        String jwt = JsonPath.read(login, "$.accessToken");
        String preRevocationTicket = ticket(jwt);
        try (Connection a = connect(ticket(jwt))) {
            mvc.perform(post("/api/auth/logout")
                .header("Authorization", "Bearer " + jwt)).andExpect(status().isNoContent());
            mvc.perform(post("/api/realtime/ticket")
                .header("Authorization", "Bearer " + jwt)).andExpect(status().isUnauthorized());
            sockets.closeRevokedSessions();
            assertEquals(1008, a.listener.closeCode.get(5, TimeUnit.SECONDS));
        }
        assertThrows(CompletionException.class, () ->
            HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .subprotocols("battleship.v1", "bn-ticket." + preRevocationTicket)
                .buildAsync(uri(""), new Listener()).join());
    }

    @Test
    void twoTabsAndRefreshedAccessKeepSocketsAlive() throws Exception {
        String login = createAndLogin();
        String jwt = JsonPath.read(login, "$.accessToken");
        String refresh = JsonPath.read(login, "$.refreshToken");
        try (Connection a = connect(ticket(jwt));
             Connection b = connect(ticket(jwt))) {
            String response = mvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            try (Connection c = connect(ticket(JsonPath.read(response, "$.accessToken")))) {
                a.socket.sendText("{\"type\":\"PING\"}", true).join();
                b.socket.sendText("{\"type\":\"PING\"}", true).join();
                assertTrue(a.listener.next().contains("\"type\":\"PONG\""));
                assertTrue(b.listener.next().contains("\"type\":\"PONG\""));
                assertNull(c.listener.noMessage());
            }
        }
    }

    private record Connection(WebSocket socket, Listener listener) implements AutoCloseable {
        @Override public void close() {
            if (!socket.isOutputClosed()) {
                try { socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(2, TimeUnit.SECONDS); }
                catch (Exception ignored) { /* Socket may have been closed by server. */ }
            }
        }
    }

    private static class Listener implements WebSocket.Listener {
        final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        private final StringBuilder fragments = new StringBuilder();

        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public java.util.concurrent.CompletionStage<?> onText(
            WebSocket socket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                messages.add(fragments.toString());
                fragments.setLength(0);
            }
            socket.request(1);
            return null;
        }
        @Override public java.util.concurrent.CompletionStage<?> onClose(
            WebSocket socket, int statusCode, String reason) {
            closeCode.complete(statusCode);
            return null;
        }
        @Override public void onError(WebSocket socket, Throwable error) {
            closeCode.completeExceptionally(error);
        }
        String next() throws Exception {
            String result = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(result, "Timed out waiting for WebSocket event");
            return result;
        }
        String noMessage() throws Exception { return messages.poll(180, TimeUnit.MILLISECONDS); }
    }
}
