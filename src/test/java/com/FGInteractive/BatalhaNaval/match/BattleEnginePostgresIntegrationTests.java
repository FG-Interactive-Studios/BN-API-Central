package com.FGInteractive.BatalhaNaval.match;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.FGInteractive.BatalhaNaval.matchmaking.service.MatchmakingService;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.*;
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
class BattleEnginePostgresIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired Environment environment;
    @Autowired MatchmakingService matchmaking;
    private record Player(long id, String jwt) {}

    private Player player() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        String email="battle-"+suffix+"@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"nickname":"Battle%s","email":"%s","password":"safe-password-123"}
                """.formatted(suffix,email))).andExpect(status().isCreated());
        String json=mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"safe-password-123"}
                """.formatted(email))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return new Player(((Number)JsonPath.read(json,"$.user.id")).longValue(),
            JsonPath.read(json,"$.accessToken"));
    }

    private void setup(Player host, Player guest) throws Exception {
        String room=mvc.perform(post("/api/matchmaking/lobbies")
            .header("Authorization","Bearer "+host.jwt))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String code=JsonPath.read(room,"$.code");
        mvc.perform(post("/api/matchmaking/lobbies/join")
            .header("Authorization","Bearer "+guest.jwt).contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\""+code+"\"}")).andExpect(status().isOk());
        for(Player p:new Player[]{host,guest}) {
            mvc.perform(put("/api/matchmaking/lobbies/me/ready")
                .header("Authorization","Bearer "+p.jwt).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ready\":true}")).andExpect(status().isOk());
        }
        for(Player p:new Player[]{host,guest}) {
            mvc.perform(put("/api/matches/me/placement")
                .header("Authorization","Bearer "+p.jwt).contentType(MediaType.APPLICATION_JSON)
                .content(fleet())).andExpect(status().isOk());
        }
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

    private String fleetOffset() {
        return """
            {"ships":[
              {"type":"CARRIER","row":5,"col":5,"orientation":"HORIZONTAL"},
              {"type":"BATTLESHIP","row":6,"col":5,"orientation":"HORIZONTAL"},
              {"type":"CRUISER","row":7,"col":5,"orientation":"HORIZONTAL"},
              {"type":"SUBMARINE","row":8,"col":5,"orientation":"HORIZONTAL"},
              {"type":"DESTROYER","row":9,"col":5,"orientation":"HORIZONTAL"}
            ]}
            """;
    }

    private String confirm(Player p) throws Exception {
        return mvc.perform(post("/api/matches/me/placement/confirm")
            .header("Authorization","Bearer "+p.jwt))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String view(Player p) throws Exception {
        return mvc.perform(get("/api/matches/me")
            .header("Authorization","Bearer "+p.jwt))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String shoot(Player p, int row, int col) throws Exception {
        return mvc.perform(post("/api/matches/me/shots")
            .header("Authorization","Bearer "+p.jwt).contentType(MediaType.APPLICATION_JSON)
            .content("{\"row\":"+row+",\"col\":"+col+"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void leave(Player p) throws Exception {
        mvc.perform(delete("/api/matchmaking/lobbies/me")
            .header("Authorization","Bearer "+p.jwt)).andExpect(status().isNoContent());
    }

    @Test void battleOnlyStartsAfterBothConfirmedAndAuthRequiredForEveryAction() throws Exception {
        mvc.perform(get("/api/matches/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/matches/me/shots").contentType(MediaType.APPLICATION_JSON)
            .content("{\"row\":1,\"col\":1}")).andExpect(status().isUnauthorized());
        Player host=player(),guest=player(),outsider=player();
        try {
            mvc.perform(get("/api/matches/me")
                .header("Authorization","Bearer "+outsider.jwt))
                .andExpect(status().isNotFound());
            setup(host,guest);
            mvc.perform(get("/api/matches/me").header("Authorization","Bearer "+host.jwt))
                .andExpect(status().isConflict());
            confirm(host);
            mvc.perform(post("/api/matches/me/shots")
                .header("Authorization","Bearer "+host.jwt)
                .contentType(MediaType.APPLICATION_JSON).content("{\"row\":0,\"col\":0}"))
                .andExpect(status().isConflict());
            String finalConfirmation=confirm(guest);
            assertEquals(true,JsonPath.read(finalConfirmation,"$.bothConfirmed"));
            assertEquals("PLAYING",JsonPath.read(view(host),"$.status"));
            assertEquals("PLAYING",JsonPath.read(view(guest),"$.status"));
            assertEquals(host.id,((Number)JsonPath.read(view(guest),"$.turnPlayerId")).longValue());
            assertEquals("PLAYING",JsonPath.read(mvc.perform(get("/api/matchmaking/lobbies/me")
                .header("Authorization","Bearer "+guest.jwt)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(),"$.status"));
            assertTrue(JsonPath.read(view(host),"$.yourShips.length()") instanceof Number);
            String snapshotGuest=view(guest);
            assertEquals(0,((Number)JsonPath.read(snapshotGuest,"$.shotsFired.length()")).intValue());
            assertTrue(snapshotGuest.contains("\"yourShips\""));
            assertFalse(snapshotGuest.contains("passwordHash"));
            String again=confirm(host); // No login/logout or resetting battle on retries
            assertEquals(JsonPath.read(finalConfirmation,"$.preparationId"),
                (String)JsonPath.read(again,"$.preparationId"));
        } finally { leave(host); }
    }

    @Test void wrongTurnRepeatedShotAndIllegalCoordinatesDoNotAdvanceBattle() throws Exception {
        Player host=player(),guest=player();
        try {
            setup(host,guest);confirm(host);confirm(guest);
            String first=view(host);
            mvc.perform(post("/api/matches/me/shots")
                .header("Authorization","Bearer "+guest.jwt).contentType(MediaType.APPLICATION_JSON)
                .content("{\"row\":0,\"col\":0}")).andExpect(status().isConflict());
            for(String bad:List.of("{}", "{\"row\":null,\"col\":0}",
                    "{\"row\":10,\"col\":0}", "{\"row\":-1,\"col\":0}")) {
                mvc.perform(post("/api/matches/me/shots")
                    .header("Authorization","Bearer "+host.jwt)
                    .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
            }
            assertEquals(((Number)JsonPath.read(first,"$.revision")).longValue(),
                ((Number)JsonPath.read(view(host),"$.revision")).longValue());
            String fired=shoot(host,9,9);
            assertEquals(false,JsonPath.read(fired,"$.shotsFired[0].hit"));
            assertEquals(guest.id,((Number)JsonPath.read(fired,"$.turnPlayerId")).longValue());
            mvc.perform(post("/api/matches/me/shots")
                .header("Authorization","Bearer "+host.jwt).contentType(MediaType.APPLICATION_JSON)
                .content("{\"row\":9,\"col\":9}")).andExpect(status().isConflict());
            shoot(guest,9,9);
            mvc.perform(post("/api/matches/me/shots")
                .header("Authorization","Bearer "+host.jwt).contentType(MediaType.APPLICATION_JSON)
                .content("{\"row\":9,\"col\":9}")).andExpect(status().isConflict());
            shoot(host,0,0);
            String opponent=view(guest);
            assertEquals(true,JsonPath.read(opponent,"$.shotsReceived[1].hit"));
            assertEquals(1,((Number)JsonPath.read(opponent,"$.shotsFired.length()")).intValue());
            assertEquals(2,((Number)JsonPath.read(view(host),"$.shotsFired.length()")).intValue());
        } finally { leave(host); }
    }

    @Test void fullFleetSinkingDecidesWinnerAndEndsGame() throws Exception {
        Player host=player(),guest=player();
        try {
            setup(host,guest);confirm(host);confirm(guest);
            int[][] occupied={{0,0},{0,1},{0,2},{0,3},{0,4},
                {1,0},{1,1},{1,2},{1,3},
                {2,0},{2,1},{2,2},{3,0},{3,1},{3,2},{4,0},{4,1}};
            int[][] misses={{9,9},{9,8},{9,7},{9,6},{9,5},{9,4},{9,3},{9,2},
                {9,1},{9,0},{8,9},{8,8},{8,7},{8,6},{8,5},{8,4}};
            String last="";
            for(int i=0;i<occupied.length;i++) {
                last=shoot(host,occupied[i][0],occupied[i][1]);
                assertEquals(true,JsonPath.read(last,"$.shotsFired["+i+"].hit"));
                if(i<occupied.length-1) {
                    assertEquals(guest.id,((Number)JsonPath.read(last,"$.turnPlayerId")).longValue());
                    shoot(guest,misses[i][0],misses[i][1]);
                }
            }
            assertEquals("FINISHED",JsonPath.read(last,"$.status"));
            assertEquals(host.id,((Number)JsonPath.read(last,"$.winnerId")).longValue());
            assertNull((Object)JsonPath.read(last,"$.turnPlayerId"));
            assertEquals("CARRIER",JsonPath.read(last,"$.shotsFired[4].sunkShip"));
            assertEquals("BATTLESHIP",JsonPath.read(last,"$.shotsFired[8].sunkShip"));
            assertEquals("CRUISER",JsonPath.read(last,"$.shotsFired[11].sunkShip"));
            assertEquals("SUBMARINE",JsonPath.read(last,"$.shotsFired[14].sunkShip"));
            assertEquals("DESTROYER",JsonPath.read(last,"$.shotsFired[16].sunkShip"));
            String losingView=view(guest);
            assertEquals("FINISHED",JsonPath.read(losingView,"$.status"));
            assertEquals(host.id,((Number)JsonPath.read(losingView,"$.winnerId")).longValue());
            mvc.perform(post("/api/matches/me/shots")
                .header("Authorization","Bearer "+guest.jwt).contentType(MediaType.APPLICATION_JSON)
                .content("{\"row\":0,\"col\":0}")).andExpect(status().isConflict());
            mvc.perform(get("/api/matchmaking/lobbies/me")
                .header("Authorization","Bearer "+host.jwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FINISHED"));
        } finally { leave(host); }
    }

    @Test void twoConcurrentShotsFromSamePlayerCannotBothSpendOneTurn() throws Exception {
        Player host=player(),guest=player();
        try {
            setup(host,guest);confirm(host);confirm(guest);
            var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
            var gate=new java.util.concurrent.CountDownLatch(1);
            try {
                java.util.concurrent.Callable<Integer> a=()->{
                    gate.await();
                    return mvc.perform(post("/api/matches/me/shots")
                        .header("Authorization","Bearer "+host.jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"row\":8,\"col\":8}"))
                        .andReturn().getResponse().getStatus();
                };
                java.util.concurrent.Callable<Integer> b=()->{
                    gate.await();
                    return mvc.perform(post("/api/matches/me/shots")
                        .header("Authorization","Bearer "+host.jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"row\":8,\"col\":9}"))
                        .andReturn().getResponse().getStatus();
                };
                var one=pool.submit(a);var two=pool.submit(b);gate.countDown();
                assertEquals(Set.of(200,409),Set.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS)));
            } finally {pool.shutdownNow();}
            assertEquals(1,((Number)JsonPath.read(view(host),"$.shotsFired.length()")).intValue());
            assertEquals(guest.id,((Number)JsonPath.read(view(host),"$.turnPlayerId")).longValue());
        } finally {leave(host);}
    }

    @Test void websocketBattleUpdatedGivesEachPlayerOnlyTheirOwnFleet() throws Exception {
        Player host=player(),guest=player();
        try {
            setup(host,guest);
            // Deliberately use DIFFERENT secret boards. Identical boards could
            // hide a regression leaking the opponent's placements.
            mvc.perform(put("/api/matches/me/placement")
                .header("Authorization","Bearer "+guest.jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(fleetOffset()))
                .andExpect(status().isOk());
            try (Connection a=connect(host);Connection b=connect(guest)) {
                confirm(host);confirm(guest);
                String forHost=a.listener.nextType("BATTLE_UPDATED");
                String forGuest=b.listener.nextType("BATTLE_UPDATED");
                assertEquals(host.id,((Number)JsonPath.read(forHost,"$.data.turnPlayerId")).longValue());
                assertEquals(host.id,((Number)JsonPath.read(forGuest,"$.data.turnPlayerId")).longValue());
                assertEquals(5,((Number)JsonPath.read(forHost,"$.data.yourShips.length()")).intValue());
                assertEquals(5,((Number)JsonPath.read(forGuest,"$.data.yourShips.length()")).intValue());
                assertEquals(0,((Number)JsonPath.read(forHost,"$.data.yourShips[1].row")).intValue());
                assertEquals(5,((Number)JsonPath.read(forGuest,"$.data.yourShips[1].row")).intValue());
                String outcome=shoot(host,5,5);
                assertEquals(true,JsonPath.read(outcome,"$.shotsFired[0].hit"));
                String hostEvent=a.listener.nextShotCount(1,"shotsFired");
                String guestEvent=b.listener.nextShotCount(1,"shotsReceived");
                assertEquals(1,((Number)JsonPath.read(hostEvent,"$.data.shotsFired.length()")).intValue());
                assertEquals(0,((Number)JsonPath.read(guestEvent,"$.data.shotsFired.length()")).intValue());
                assertEquals(1,((Number)JsonPath.read(guestEvent,"$.data.shotsReceived.length()")).intValue());
                assertEquals(1,((Number)JsonPath.read(view(guest),"$.shotsReceived.length()")).intValue());
            }
        } finally {leave(host);}
    }


    @Test void forfeitRequiresJwtAndPreservesResultWhenOnePlayerLeaves() throws Exception {
        mvc.perform(post("/api/matches/me/forfeit")).andExpect(status().isUnauthorized());
        Player host=player(), guest=player();
        try {
            setup(host,guest);
            mvc.perform(post("/api/matches/me/forfeit")
                .header("Authorization","Bearer "+host.jwt)).andExpect(status().isConflict());
            confirm(host);confirm(guest);
            String lost=mvc.perform(post("/api/matches/me/forfeit")
                .header("Authorization","Bearer "+host.jwt))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertEquals("ABANDONED",JsonPath.read(lost,"$.finishReason"));
            assertEquals(guest.id,((Number)JsonPath.read(lost,"$.winnerId")).longValue());
            assertEquals("FINISHED",JsonPath.read(view(guest),"$.status"));
            mvc.perform(post("/api/matches/me/forfeit")
                .header("Authorization","Bearer "+host.jwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision")
                    .value(((Number)JsonPath.read(lost,"$.revision")).intValue()));
            mvc.perform(post("/api/matches/me/forfeit")
                .header("Authorization","Bearer "+guest.jwt))
                .andExpect(status().isConflict());
            leave(host);
            // The opponent still has access to the result after the loser exits.
            assertEquals("ABANDONED",JsonPath.read(view(guest),"$.finishReason"));
            mvc.perform(get("/api/matches/me")
                .header("Authorization","Bearer "+host.jwt)).andExpect(status().isNotFound());
        } finally { leave(host); leave(guest); }
    }

    @Test void leavingDuringPlayCountsAsSurrenderAndRequiresASecondLeaveToExitResults() throws Exception {
        Player host=player(), guest=player();
        try {
            setup(host,guest);confirm(host);confirm(guest);
            leave(guest); // First DELETE is surrender; result remains inspectable.
            String game=view(guest);
            assertEquals("FINISHED",JsonPath.read(game,"$.status"));
            assertEquals("ABANDONED",JsonPath.read(game,"$.finishReason"));
            assertEquals(host.id,((Number)JsonPath.read(game,"$.winnerId")).longValue());
            assertEquals("FINISHED",JsonPath.read(view(host),"$.status"));
            leave(guest); // Second DELETE dismisses the finished result.
            assertEquals("FINISHED",JsonPath.read(view(host),"$.status"));
            mvc.perform(get("/api/matches/me")
                .header("Authorization","Bearer "+guest.jwt)).andExpect(status().isNotFound());
            leave(host);
            mvc.perform(get("/api/matchmaking/lobbies/me")
                .header("Authorization","Bearer "+host.jwt)).andExpect(status().isNotFound());
        } finally { leave(host);leave(guest); }
    }

    @Test void websocketDisconnectCanRecoverOrTimeOutWithoutLeakingBoard() throws Exception {
        Player host=player(), guest=player();
        try {
            setup(host,guest);
            try(Connection guestSocket=connect(guest)) {
                confirm(host);confirm(guest);
                String initial=view(host);
                @SuppressWarnings("unchecked")
                Map<String,Object> deadlines=JsonPath.read(initial,"$.reconnectDeadlines");
                assertTrue(deadlines.containsKey(Long.toString(host.id)));
                // The disconnected host has grace: no immediate defeat.
                assertEquals("PLAYING",JsonPath.read(initial,"$.status"));
                try(Connection hostSocket=connect(host)) {
                    boolean cleared=false;
                    for(int i=0;i<50;i++) {
                        @SuppressWarnings("unchecked")
                        Map<String,Object> next=JsonPath.read(view(host),"$.reconnectDeadlines");
                        if(!next.containsKey(Long.toString(host.id))) {cleared=true;break;}
                        Thread.sleep(30);
                    }
                    assertTrue(cleared,"Reconnecting should cancel the deadline");
                }
                boolean waiting=false;
                for(int i=0;i<50;i++) {
                    @SuppressWarnings("unchecked")
                    Map<String,Object> next=JsonPath.read(view(guest),"$.reconnectDeadlines");
                    if(next.containsKey(Long.toString(host.id))) {waiting=true;break;}
                    Thread.sleep(30);
                }
                assertTrue(waiting,"Closing the last tab must start a new grace period");
                matchmaking.sweepDisconnectedAt(Instant.now().plusSeconds(125));
                String finished=view(guest);
                assertEquals("DISCONNECT_TIMEOUT",JsonPath.read(finished,"$.finishReason"));
                assertEquals(guest.id,((Number)JsonPath.read(finished,"$.winnerId")).longValue());
                String event=guestSocket.listener.nextType("BATTLE_UPDATED");
                // An old queued snapshot may be received first; REST is authoritative.
                assertNotNull(event);
            }
        } finally { leave(host);leave(guest); }
    }

    @Test void twoAbsentSocketsExpireIntoDrawRatherThanArbitraryWinner() throws Exception {
        Player host=player(),guest=player();
        try {
            setup(host,guest);confirm(host);confirm(guest);
            matchmaking.sweepDisconnectedAt(Instant.now().plusSeconds(125));
            String result=view(host);
            assertEquals("BOTH_DISCONNECTED",JsonPath.read(result,"$.finishReason"));
            assertEquals("FINISHED",JsonPath.read(result,"$.status"));
            assertNull((Object)JsonPath.read(result,"$.winnerId"));
        } finally {leave(host);leave(guest);}
    }

    private Connection connect(Player p) throws Exception {
        String response=mvc.perform(post("/api/realtime/ticket")
            .header("Authorization","Bearer "+p.jwt)).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String ticket=JsonPath.read(response,"$.ticket");
        Listener listener=new Listener();
        WebSocket socket=HttpClient.newHttpClient().newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .subprotocols("battleship.v1","bn-ticket."+ticket)
            .buildAsync(URI.create("ws://localhost:"+
                environment.getRequiredProperty("local.server.port")+"/ws"),listener).join();
        listener.nextType("CONNECTED");
        return new Connection(socket,listener);
    }
    private record Connection(WebSocket socket,Listener listener) implements AutoCloseable {
        @Override public void close() {
            if (!socket.isOutputClosed())
                try { socket.sendClose(WebSocket.NORMAL_CLOSURE,"").get(2,TimeUnit.SECONDS); }
                catch(Exception ignored) {}
        }
    }
    private static class Listener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> queue=new LinkedBlockingQueue<>();
        private final StringBuilder fragments=new StringBuilder();
        @Override public void onOpen(WebSocket socket){socket.request(1);}
        @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket socket,
                CharSequence message,boolean last){
            fragments.append(message);
            if(last){queue.add(fragments.toString());fragments.setLength(0);}
            socket.request(1);return null;
        }
        String nextType(String type)throws Exception {
            return next(event->type.equals(JsonPath.read(event,"$.type")));
        }
        String nextShotCount(int count,String list)throws Exception {
            return next(event->"BATTLE_UPDATED".equals(JsonPath.read(event,"$.type"))
                && ((Number)JsonPath.read(event,"$.data."+list+".length()")).intValue()==count);
        }
        String next(java.util.function.Predicate<String> predicate)throws Exception{
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
            while(System.nanoTime()<deadline) {
                String event=queue.poll(200,TimeUnit.MILLISECONDS);
                if(event!=null && predicate.test(event))return event;
            }
            fail("No matching WebSocket event");return "";
        }
    }
}
