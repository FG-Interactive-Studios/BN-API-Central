package com.FGInteractive.BatalhaNaval.match;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.UUID;
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
class MatchPlacementPostgresIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired Environment environment;

    private record Player(long id, String jwt) {}

    private Player player() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "fleet-" + suffix + "@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"nickname":"Fleet%s","email":"%s","password":"safe-password-123"}
                """.formatted(suffix, email)))
            .andExpect(status().isCreated());
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"safe-password-123"}
                """.formatted(email)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Player(((Number)JsonPath.read(login,"$.user.id")).longValue(),
            JsonPath.read(login,"$.accessToken"));
    }

    private String create(Player player) throws Exception {
        String json = mvc.perform(post("/api/matchmaking/lobbies")
            .header("Authorization", "Bearer " + player.jwt))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.code");
    }

    private void join(Player player, String code) throws Exception {
        mvc.perform(post("/api/matchmaking/lobbies/join")
            .header("Authorization", "Bearer " + player.jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"code":"%s"}
                """.formatted(code)))
            .andExpect(status().isOk());
    }

    private void ready(Player player) throws Exception {
        mvc.perform(put("/api/matchmaking/lobbies/me/ready")
            .header("Authorization", "Bearer " + player.jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ready\":true}"))
            .andExpect(status().isOk());
    }

    private void leave(Player player) throws Exception {
        mvc.perform(delete("/api/matchmaking/lobbies/me")
            .header("Authorization", "Bearer " + player.jwt))
            .andExpect(status().isNoContent());
    }

    private String state(Player player) throws Exception {
        return mvc.perform(get("/api/matches/me/placement")
            .header("Authorization", "Bearer " + player.jwt))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private String set(Player player, String payload) throws Exception {
        return mvc.perform(put("/api/matches/me/placement")
            .header("Authorization", "Bearer " + player.jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private String random(Player player) throws Exception {
        return mvc.perform(post("/api/matches/me/placement/random")
            .header("Authorization", "Bearer " + player.jwt))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private String confirm(Player player) throws Exception {
        return mvc.perform(post("/api/matches/me/placement/confirm")
            .header("Authorization", "Bearer " + player.jwt))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private String fleet() {
        return """
            {"ships":[
              {"type":"CARRIER","row":0,"col":0,"orientation":"HORIZONTAL"},
              {"type":"BATTLESHIP","row":1,"col":0,"orientation":"HORIZONTAL"},
              {"type":"CRUISER","row":2,"col":0,"orientation":"HORIZONTAL"},
              {"type":"SUBMARINE","row":3,"col":0,"orientation":"HORIZONTAL"},
              {"type":"DESTROYER","row":4,"col":0,"orientation":"HORIZONTAL"}
            ]}
            """;
    }

    @Test void cannotPlaceBeforeTwoPlayersAreReadyOrWithoutJwt() throws Exception {
        mvc.perform(get("/api/matches/me/placement")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/matches/me/placement")
            .contentType(MediaType.APPLICATION_JSON).content(fleet()))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/matches/me/placement/random"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/matches/me/placement/confirm"))
            .andExpect(status().isUnauthorized());

        Player host = player(), guest = player(), outsider = player();
        try {
            mvc.perform(get("/api/matches/me/placement")
                .header("Authorization","Bearer " + outsider.jwt))
                .andExpect(status().isNotFound());
            String code = create(host);
            mvc.perform(get("/api/matches/me/placement")
                .header("Authorization","Bearer " + host.jwt))
                .andExpect(status().isConflict());
            join(guest, code);
            ready(host);
            mvc.perform(post("/api/matches/me/placement/random")
                .header("Authorization","Bearer " + guest.jwt))
                .andExpect(status().isConflict());
            ready(guest);
            assertEquals(10, ((Number)JsonPath.read(state(host),"$.boardSize")).intValue());
            mvc.perform(get("/api/matches/me/placement")
                .header("Authorization","Bearer " + outsider.jwt))
                .andExpect(status().isNotFound());
        } finally { leave(host); }
    }

    @Test void manuallyPlacedFleetIsPrivateAndBothConfirmationsAreStable() throws Exception {
        Player a=player(), b=player();
        try {
            String code=create(a);
            join(b,code);
            ready(a); ready(b);
            String initial=state(a);
            String placementId=JsonPath.read(initial,"$.preparationId");
            assertEquals(false, JsonPath.read(initial,"$.fleetPlaced"));
            String host=set(a,fleet());
            assertEquals(5, ((Number)JsonPath.read(host,"$.yourShips.length()")).intValue());
            assertEquals(true, JsonPath.read(host,"$.fleetPlaced"));
            assertEquals(false, JsonPath.read(host,"$.opponentPlaced"));
            String guest=state(b);
            assertEquals(0, ((Number)JsonPath.read(guest,"$.yourShips.length()")).intValue());
            assertEquals(true, JsonPath.read(guest,"$.opponentPlaced"));
            assertFalse(guest.contains("HORIZONTAL"));
            // Ship identifiers are public mode metadata; only private positions are secret.
            assertFalse(guest.contains("\"orientation\""));

            String auto=random(b);
            assertEquals(5, ((Number)JsonPath.read(auto,"$.yourShips.length()")).intValue());
            assertEquals(true, JsonPath.read(state(a),"$.opponentPlaced"));
            String firstConfirmation=confirm(a);
            assertEquals(true, JsonPath.read(firstConfirmation,"$.fleetConfirmed"));
            assertEquals(false, JsonPath.read(firstConfirmation,"$.bothConfirmed"));
            String finalConfirmation=confirm(b);
            assertEquals(true, JsonPath.read(finalConfirmation,"$.bothConfirmed"));
            assertEquals(true, JsonPath.read(state(a),"$.opponentConfirmed"));
            assertEquals(placementId, (String)JsonPath.read(state(a),"$.preparationId"));
            assertEquals(5, ((Number)JsonPath.read(state(a),"$.yourShips.length()")).intValue());
            assertEquals(5, ((Number)JsonPath.read(state(b),"$.yourShips.length()")).intValue());
            long revision=((Number)JsonPath.read(finalConfirmation,"$.revision")).longValue();
            assertEquals(revision, ((Number)JsonPath.read(confirm(b),"$.revision")).longValue());
            mvc.perform(put("/api/matches/me/placement")
                .header("Authorization","Bearer " + a.jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(fleet())).andExpect(status().isConflict());
            mvc.perform(post("/api/matches/me/placement/random")
                .header("Authorization","Bearer " + b.jwt))
                .andExpect(status().isConflict());
        } finally { leave(a); }
    }

    @Test void invalidFleetsNeverModifyExistingValidBoard() throws Exception {
        Player a=player(), b=player();
        try {
            String code=create(a);join(b,code);ready(a);ready(b);
            String valid=set(a,fleet());
            long revision=((Number)JsonPath.read(valid,"$.revision")).longValue();
            for(String payload : java.util.List.of(
                "{}", "{\"ships\":[]}", "{\"ships\":null}",
                fleet().replace("\"row\":0","\"row\":10"),
                fleet().replace("\"col\":0","\"col\":9"),
                fleet().replace("\"row\":1","\"row\":0"),
                fleet().replace("\"type\":\"DESTROYER\"","\"type\":\"CARRIER\""),
                fleet().replace("\"orientation\":\"HORIZONTAL\"","\"orientation\":null"),
                fleet().replace("\"row\":0","\"row\":null"),
                fleet().replace("\"row\":0","\"row\":-1"),
                fleet().replace("\"type\":\"CARRIER\"","\"type\":\"UNKNOWN\"")
            )) {
                mvc.perform(put("/api/matches/me/placement")
                    .header("Authorization","Bearer " + a.jwt)
                    .contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isBadRequest());
                assertEquals(revision, ((Number)JsonPath.read(state(a),"$.revision")).longValue());
            }
            assertEquals(5,((Number)JsonPath.read(state(a),"$.yourShips.length()")).intValue());
            mvc.perform(post("/api/matches/me/placement/confirm")
                .header("Authorization","Bearer " + b.jwt))
                .andExpect(status().isConflict());
            assertEquals(false, JsonPath.read(state(b),"$.fleetConfirmed"));
        } finally { leave(a); }
    }

    @Test void leavingDiscardsSecretBoardAndRejoiningStartsFreshPreparation() throws Exception {
        Player host=player(), guest=player();
        try {
            String code=create(host);join(guest,code);ready(host);ready(guest);
            String original=set(host,fleet());
            String oldRound=JsonPath.read(original,"$.preparationId");
            leave(guest);
            mvc.perform(get("/api/matches/me/placement")
                .header("Authorization","Bearer " + host.jwt))
                .andExpect(status().isConflict());
            join(guest, code);ready(host);ready(guest);
            String newRound=state(host);
            assertNotEquals(oldRound, (String)JsonPath.read(newRound,"$.preparationId"));
            assertEquals(false, JsonPath.read(newRound,"$.fleetPlaced"));
            assertEquals(0, ((Number)JsonPath.read(newRound,"$.yourShips.length()")).intValue());
            assertEquals(false, JsonPath.read(state(guest),"$.opponentPlaced"));
        } finally { leave(host); }
    }

    @Test void realtimeEventsNeverLeakOpponentsShipCoordinates() throws Exception {
        Player host=player(), guest=player();
        try {
            String code=create(host);join(guest,code);ready(host);ready(guest);
            try(Connection a=connect(host);Connection b=connect(guest)) {
                set(host,fleet());
                String eventForHost=a.listener.nextType("PREPARATION_UPDATED");
                String eventForGuest=b.listener.nextType("PREPARATION_UPDATED");
                assertEquals(5, ((Number)JsonPath.read(eventForHost,"$.data.yourShips.length()")).intValue());
                assertEquals(0, ((Number)JsonPath.read(eventForGuest,"$.data.yourShips.length()")).intValue());
                assertEquals(true, JsonPath.read(eventForGuest,"$.data.opponentPlaced"));
                assertFalse(eventForGuest.contains("HORIZONTAL"));
                random(guest);
                assertEquals(5, ((Number)JsonPath.read(b.listener.nextType("PREPARATION_UPDATED"),
                    "$.data.yourShips.length()")).intValue());
                String view=a.listener.nextType("PREPARATION_UPDATED");
                assertEquals(true, JsonPath.read(view,"$.data.opponentPlaced"));
                assertEquals(5, ((Number)JsonPath.read(view,"$.data.yourShips.length()")).intValue());
            }
            // Reconnecting does not erase a fleet, and GET restores the own board.
            assertEquals(5,((Number)JsonPath.read(state(host),"$.yourShips.length()")).intValue());
        } finally { leave(host); }
    }

    private Connection connect(Player player) throws Exception {
        String json=mvc.perform(post("/api/realtime/ticket")
            .header("Authorization","Bearer " + player.jwt))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String ticket=JsonPath.read(json,"$.ticket");
        Listener listener=new Listener();
        WebSocket socket=HttpClient.newHttpClient().newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .subprotocols("battleship.v1","bn-ticket." + ticket)
            .buildAsync(URI.create("ws://localhost:" +
                environment.getRequiredProperty("local.server.port") + "/ws"),listener).join();
        listener.nextType("CONNECTED");
        return new Connection(socket,listener);
    }

    private record Connection(WebSocket socket, Listener listener) implements AutoCloseable {
        @Override public void close() {
            if(!socket.isOutputClosed()) {
                try { socket.sendClose(WebSocket.NORMAL_CLOSURE,"").get(2,TimeUnit.SECONDS); }
                catch(Exception ignored) {}
            }
        }
    }

    private static class Listener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages=new LinkedBlockingQueue<>();
        private final StringBuilder fragments=new StringBuilder();
        @Override public void onOpen(WebSocket socket) {socket.request(1);}
        @Override public java.util.concurrent.CompletionStage<?> onText(
            WebSocket socket,CharSequence text,boolean last) {
            fragments.append(text);
            if(last) {messages.add(fragments.toString());fragments.setLength(0);}
            socket.request(1);
            return null;
        }
        String nextType(String type) throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
            while(System.nanoTime()<deadline) {
                String event=messages.poll(200,TimeUnit.MILLISECONDS);
                if(event!=null && type.equals(JsonPath.read(event,"$.type"))) return event;
            }
            fail("No "+type+" event received");return "";
        }
    }
}
