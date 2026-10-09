package com.FGInteractive.BatalhaNaval.matchmaking;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.FGInteractive.BatalhaNaval.shared.websocket.RealtimeWebSocketHandler;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
class PrivateLobbiesPostgresIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired Environment environment;
    @Autowired RealtimeWebSocketHandler sockets;

    private record Player(long id, String jwt) {}

    private Player player() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "lobby-" + suffix + "@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"nickname":"Lobby%s","email":"%s","password":"safe-password-123"}
                """.formatted(suffix, email))).andExpect(status().isCreated());
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"safe-password-123"}
                """.formatted(email))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return new Player(((Number) JsonPath.read(login, "$.user.id")).longValue(),
            JsonPath.read(login, "$.accessToken"));
    }

    private String create(Player user) throws Exception {
        return mvc.perform(post("/api/matchmaking/lobbies")
            .header("Authorization", "Bearer " + user.jwt))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("WAITING"))
            .andExpect(jsonPath("$.players.length()").value(1))
            .andExpect(jsonPath("$.capacity").value(2))
            .andReturn().getResponse().getContentAsString();
    }

    private String join(Player user, String code) throws Exception {
        return mvc.perform(post("/api/matchmaking/lobbies/join")
            .header("Authorization", "Bearer " + user.jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + code + "\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private String ready(Player user, boolean state) throws Exception {
        return mvc.perform(put("/api/matchmaking/lobbies/me/ready")
            .header("Authorization", "Bearer " + user.jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ready\":" + state + "}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private void leave(Player user) throws Exception {
        mvc.perform(delete("/api/matchmaking/lobbies/me")
            .header("Authorization", "Bearer " + user.jwt))
            .andExpect(status().isNoContent());
    }

    @Test
    void roomApiRequiresAuthenticationAndNeverNeedsUserId() throws Exception {
        mvc.perform(post("/api/matchmaking/lobbies")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/matchmaking/lobbies/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/matchmaking/lobbies/join")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"ABCDEF\"}")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/matchmaking/lobbies/me/ready")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ready\":true}")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/matchmaking/lobbies/me")).andExpect(status().isUnauthorized());

        Player a = player();
        String room = create(a);
        assertEquals(a.id, ((Number) JsonPath.read(room, "$.players[0].id")).longValue());
        assertNotNull(JsonPath.read(room, "$.code"));
        mvc.perform(get("/api/matchmaking/lobbies/me")
            .header("Authorization", "Bearer " + a.jwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(JsonPath.read(room, "$.code")));
        leave(a);
    }

    @Test
    void exactTwoPlayersBecomeReadyAndTransitionToPreparation() throws Exception {
        Player a = player(), b = player(), c = player();
        String room = create(a);
        String code = JsonPath.read(room, "$.code");
        String hostReady = ready(a, true);
        assertEquals(true, JsonPath.read(hostReady, "$.players[0].ready"));

        String joined = join(b, code.toLowerCase());
        assertEquals("WAITING", JsonPath.read(joined, "$.status"));
        assertEquals(2, ((Number) JsonPath.read(joined, "$.players.length()")).intValue());
        assertEquals(false, JsonPath.read(joined, "$.players[0].ready"));
        assertEquals(b.id, ((Number) JsonPath.read(joined, "$.players[1].id")).longValue());
        assertNotNull(JsonPath.read(joined, "$.players[1].nickname"));
        assertNotNull(JsonPath.read(joined, "$.players[1].avatarId"));
        assertFalse(joined.contains("email"));
        assertFalse(joined.contains("passwordHash"));

        mvc.perform(post("/api/matchmaking/lobbies/join")
            .header("Authorization", "Bearer " + c.jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + code + "\"}"))
            .andExpect(status().isConflict());

        ready(a, true);
        String prepared = ready(b, true);
        assertEquals("PREPARING", JsonPath.read(prepared, "$.status"));
        assertEquals(true, JsonPath.read(prepared, "$.players[0].ready"));
        assertEquals(true, JsonPath.read(prepared, "$.players[1].ready"));
        assertTrue(((Number) JsonPath.read(prepared, "$.revision")).longValue()
            > ((Number) JsonPath.read(room, "$.revision")).longValue());

        mvc.perform(put("/api/matchmaking/lobbies/me/ready")
            .header("Authorization", "Bearer " + b.jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ready\":false}"))
            .andExpect(status().isConflict());

        mvc.perform(get("/api/matchmaking/lobbies/me")
            .header("Authorization", "Bearer " + a.jwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PREPARING"));

        // Repeat "ready: true" is idempotent even after the phase transition.
        String same = ready(a, true);
        assertEquals(((Number) JsonPath.read(prepared, "$.revision")).longValue(),
            ((Number) JsonPath.read(same, "$.revision")).longValue());
        leave(a);
        mvc.perform(get("/api/matchmaking/lobbies/me")
            .header("Authorization", "Bearer " + b.jwt))
            .andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidCodesMultipleRoomsAndUnauthorizedMutations() throws Exception {
        Player a = player(), b = player(), outsider = player();
        String code = JsonPath.read(create(a), "$.code");
        String theirCode = JsonPath.read(create(outsider), "$.code");

        mvc.perform(put("/api/matchmaking/lobbies/me/ready")
            .header("Authorization", "Bearer " + b.jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ready\":true}"))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/matchmaking/lobbies/join")
            .header("Authorization", "Bearer " + b.jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"!!!\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/matchmaking/lobbies/join")
            .header("Authorization", "Bearer " + b.jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"ZZZZZZ\"}"))
            .andExpect(status().isNotFound());

        join(b, code);
        String replay = join(b, code);
        assertEquals(code, JsonPath.read(replay, "$.code"));
        mvc.perform(post("/api/matchmaking/lobbies")
            .header("Authorization", "Bearer " + b.jwt)).andExpect(status().isConflict());
        mvc.perform(post("/api/matchmaking/lobbies/join")
            .header("Authorization", "Bearer " + b.jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + theirCode + "\"}"))
            .andExpect(status().isConflict());

        // A malicious userId field cannot switch which authenticated account is joined.
        Player extra = player();
        mvc.perform(post("/api/matchmaking/lobbies/join")
            .header("Authorization", "Bearer " + extra.jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + theirCode + "\",\"userId\":" + a.id + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.players[1].id").value(extra.id));
        leave(extra);
        leave(outsider);
        leave(a);
        leave(b);
    }

    @Test
    void leavingAndHostClosingAreScopedAndFreePlayersToJoinAgain() throws Exception {
        Player host = player(), guest = player();
        String code = JsonPath.read(create(host), "$.code");
        join(guest, code);
        ready(host, true);
        ready(guest, true);

        // A guest leaving during PREPARING returns the host to WAITING.
        leave(guest);
        mvc.perform(get("/api/matchmaking/lobbies/me")
            .header("Authorization", "Bearer " + host.jwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("WAITING"))
            .andExpect(jsonPath("$.players.length()").value(1))
            .andExpect(jsonPath("$.players[0].ready").value(false));
        String rejoined = join(guest, code);
        assertEquals(code, JsonPath.read(rejoined, "$.code"));

        leave(host); // Closing the host room evicts guest too.
        mvc.perform(get("/api/matchmaking/lobbies/me")
            .header("Authorization", "Bearer " + guest.jwt))
            .andExpect(status().isNotFound());
        leave(guest); // Idempotent.
        create(guest);
        leave(guest);
    }


    @Test
    void concurrentJoinsCannotOccupyTheSameFinalSlot() throws Exception {
        Player host = player(), first = player(), second = player();
        String code = JsonPath.read(create(host), "$.code");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> firstJoin = () -> {
                start.await();
                return mvc.perform(post("/api/matchmaking/lobbies/join")
                    .header("Authorization", "Bearer " + first.jwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"code":"%s"}
                        """.formatted(code))
                    .andReturn().getResponse().getStatus();
            };
            java.util.concurrent.Callable<Integer> secondJoin = () -> {
                start.await();
                return mvc.perform(post("/api/matchmaking/lobbies/join")
                    .header("Authorization", "Bearer " + second.jwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"code":"%s"}
                        """.formatted(code))
                    .andReturn().getResponse().getStatus();
            };
            var one = executor.submit(firstJoin);
            var two = executor.submit(secondJoin);
            start.countDown();
            int statusA = one.get(10, TimeUnit.SECONDS);
            int statusB = two.get(10, TimeUnit.SECONDS);
            assertEquals(java.util.Set.of(200, 409), java.util.Set.of(statusA, statusB));
            mvc.perform(get("/api/matchmaking/lobbies/me")
                .header("Authorization", "Bearer " + host.jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(2));
        } finally {
            executor.shutdownNow();
            leave(host);
        }
    }

    @Test
    void websocketPresenceIsPrivateAndReconnectResendsSnapshot() throws Exception {
        Player a = player(), b = player(), outsider = player();
        String code = JsonPath.read(create(a), "$.code");
        join(b, code);
        create(outsider);

        try (Connection c = connect(outsider)) {
            c.listener.awaitType("LOBBY_UPDATED"); // Ignore outsider snapshot.
            try (Connection hostSocket = connect(a)) {
                String online = hostSocket.listener.awaitType("LOBBY_UPDATED");
                assertEquals(true, JsonPath.read(online, "$.data.players[0].connected"));
                assertNull(c.listener.noMessage());

                try (Connection guestSocket = connect(b)) {
                    String bothOnline = hostSocket.listener.awaitType("LOBBY_UPDATED");
                    assertEquals(true, JsonPath.read(bothOnline, "$.data.players[1].connected"));
                    String guestSnapshot = guestSocket.listener.awaitType("LOBBY_UPDATED");
                    assertEquals(code, JsonPath.read(guestSnapshot, "$.data.code"));

                    ready(a, true);
                    ready(b, true);
                    String status = guestSocket.listener.awaitStatus("PREPARING");
                    assertEquals("PREPARING", JsonPath.read(status, "$.data.status"));
                    assertNull(c.listener.noMessage());
                }

                String left = hostSocket.listener.awaitDisconnectedGuest();
                assertEquals(false, JsonPath.read(left, "$.data.players[1].connected"));
            }

            // A browser reload reconnects to the existing room (no duplicate join).
            try (Connection fresh = connect(a)) {
                String snapshot = fresh.listener.awaitType("LOBBY_UPDATED");
                assertEquals(code, JsonPath.read(snapshot, "$.data.code"));
                assertEquals("PREPARING", JsonPath.read(snapshot, "$.data.status"));
            }
        }
        leave(a);
        leave(outsider);
    }

    private Connection connect(Player player) throws Exception {
        String json = mvc.perform(post("/api/realtime/ticket")
            .header("Authorization", "Bearer " + player.jwt))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String ticket = JsonPath.read(json, "$.ticket");
        Listener listener = new Listener();
        URI url = URI.create("ws://localhost:" + environment.getRequiredProperty("local.server.port") + "/ws");
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .subprotocols("battleship.v1", "bn-ticket." + ticket)
            .buildAsync(url, listener).join();
        assertTrue(listener.awaitType("CONNECTED").contains("\"type\":\"CONNECTED\""));
        return new Connection(socket, listener);
    }

    private record Connection(WebSocket socket, Listener listener) implements AutoCloseable {
        @Override public void close() {
            if (!socket.isOutputClosed()) {
                try { socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(2, TimeUnit.SECONDS); }
                catch (Exception ignored) {}
            }
        }
    }

    private static class Listener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder fragments = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public java.util.concurrent.CompletionStage<?> onText(
                WebSocket socket, CharSequence chunk, boolean last) {
            fragments.append(chunk);
            if (last) {
                messages.add(fragments.toString());
                fragments.setLength(0);
            }
            socket.request(1);
            return null;
        }
        @Override public java.util.concurrent.CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            closed.complete(code);
            return null;
        }
        @Override public void onError(WebSocket socket, Throwable error) {
            closed.completeExceptionally(error);
        }

        String awaitType(String type) throws Exception {
            return until(event -> ("\"" + type + "\"").equals("\"" + JsonPath.read(event, "$.type") + "\""));
        }
        String awaitStatus(String status) throws Exception {
            return until(event -> "LOBBY_UPDATED".equals(JsonPath.read(event, "$.type"))
                && status.equals(JsonPath.read(event, "$.data.status")));
        }
        String awaitDisconnectedGuest() throws Exception {
            return until(event -> "LOBBY_UPDATED".equals(JsonPath.read(event, "$.type"))
                && Boolean.FALSE.equals(JsonPath.read(event, "$.data.players[1].connected")));
        }
        String noMessage() throws Exception { return messages.poll(180, TimeUnit.MILLISECONDS); }
        private String until(java.util.function.Predicate<String> matcher) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            while (System.nanoTime() < deadline) {
                String event = messages.poll(200, TimeUnit.MILLISECONDS);
                if (event != null && matcher.test(event)) return event;
            }
            fail("Timed out awaiting WebSocket event");
            return "";
        }
    }
}
