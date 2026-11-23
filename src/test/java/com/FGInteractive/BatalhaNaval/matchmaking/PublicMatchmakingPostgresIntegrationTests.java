package com.FGInteractive.BatalhaNaval.matchmaking;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment=WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("ci")
class PublicMatchmakingPostgresIntegrationTests {
    @Autowired MockMvc mvc;
    private record Player(long id,String token) {}
    private Player player() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        String email="public-"+suffix+"@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"nickname\":\"Search"+suffix+"\",\"email\":\""+email+"\",\"password\":\"safe-password-123\"}"))
            .andExpect(status().isCreated());
        String login=mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\""+email+"\",\"password\":\"safe-password-123\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Player(((Number)JsonPath.read(login,"$.user.id")).longValue(),
            JsonPath.read(login,"$.accessToken"));
    }
    private String search(Player player,String modeId) throws Exception {
        return mvc.perform(post("/api/matchmaking/queue")
            .header("Authorization","Bearer "+player.token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"modeId\":\""+modeId+"\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    private String status(Player player) throws Exception {
        return mvc.perform(get("/api/matchmaking/queue/me")
            .header("Authorization","Bearer "+player.token)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }
    private void cancel(Player player) throws Exception {
        mvc.perform(delete("/api/matchmaking/queue/me")
            .header("Authorization","Bearer "+player.token)).andExpect(status().isNoContent());
    }
    private void leave(Player player) throws Exception {
        mvc.perform(delete("/api/matchmaking/lobbies/me")
            .header("Authorization","Bearer "+player.token)).andExpect(status().isNoContent());
    }

    @Test void queueRequiresAuthenticationAndDoesNotRevealOtherPlayers() throws Exception {
        mvc.perform(post("/api/matchmaking/queue")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/matchmaking/queue/me")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/matchmaking/queue/me")).andExpect(status().isUnauthorized());
        Player first=player();
        try {
            String waiting=search(first,"classic");
            assertEquals("SEARCHING",JsonPath.read(waiting,"$.status"));
            assertEquals("classic",JsonPath.read(waiting,"$.modeId"));
            assertNull(JsonPath.read(waiting,"$.room"));
            assertEquals(waiting,search(first,"classic")); // Repeated POST is idempotent
            assertEquals("SEARCHING",JsonPath.read(status(first),"$.status"));
            mvc.perform(get("/api/matchmaking/lobbies/me")
                .header("Authorization","Bearer "+first.token)).andExpect(status().isNotFound());
            mvc.perform(post("/api/matchmaking/lobbies").header("Authorization","Bearer "+first.token))
                .andExpect(status().isConflict());
        } finally {cancel(first);}
    }

    @Test void sameModePlayersMatchAndContinueWithExistingReadyFlow() throws Exception {
        Player a=player(),b=player(),outsider=player();
        try {
            search(a,"classic");
            String joined=search(b,"classic");
            assertEquals("MATCHED",JsonPath.read(joined,"$.status"));
            assertEquals("WAITING",JsonPath.read(joined,"$.room.status"));
            assertEquals(true,JsonPath.read(joined,"$.room.publicMatch"));
            assertEquals(2,((Number)JsonPath.read(joined,"$.room.players.length()")).intValue());
            assertEquals(a.id,((Number)JsonPath.read(joined,"$.room.players[0].id")).longValue());
            assertEquals(b.id,((Number)JsonPath.read(joined,"$.room.players[1].id")).longValue());
            String code=JsonPath.read(joined,"$.room.code");
            assertEquals(code,JsonPath.read(status(a),"$.room.code"));
            assertEquals("MATCHED",JsonPath.read(search(b,"classic"),"$.status"));
            mvc.perform(post("/api/matchmaking/lobbies/join")
                .header("Authorization","Bearer "+outsider.token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\""+code+"\"}"))
                .andExpect(status().isConflict());
            mvc.perform(delete("/api/matchmaking/queue/me")
                .header("Authorization","Bearer "+a.token)).andExpect(status().isConflict());
            mvc.perform(put("/api/matchmaking/lobbies/me/ready")
                .header("Authorization","Bearer "+a.token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"ready\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WAITING"));
            mvc.perform(put("/api/matchmaking/lobbies/me/ready")
                .header("Authorization","Bearer "+b.token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"ready\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PREPARING"));
            mvc.perform(get("/api/matches/me/placement")
                .header("Authorization","Bearer "+a.token)).andExpect(status().isOk());
        } finally {leave(a);leave(b);cancel(outsider);}
    }

    @Test void cancellationAndPrivateRoomsNeverLeakIntoQueue() throws Exception {
        Player searching=player(),other=player(),privateHost=player(),third=player();
        try {
            String privateCode=JsonPath.read(mvc.perform(post("/api/matchmaking/lobbies")
                .header("Authorization","Bearer "+privateHost.token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"modeId\":\"classic\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"$.code");
            search(searching,"classic");
            cancel(searching);
            mvc.perform(get("/api/matchmaking/queue/me")
                .header("Authorization","Bearer "+searching.token)).andExpect(status().isNotFound());
            assertEquals("SEARCHING",JsonPath.read(search(other,"classic"),"$.status"));
            assertEquals("MATCHED",JsonPath.read(search(third,"classic"),"$.status"));
            assertNotEquals(privateCode,JsonPath.read(status(other),"$.room.code"));
            cancel(searching); // Idempotent
        } finally {leave(privateHost);leave(other);leave(third);cancel(searching);}
    }

    @Test void ifPlayerLeavesPublicRoomOtherReturnsToQueue() throws Exception {
        Player host=player(),guest=player(),replacement=player();
        try {
            search(host,"classic");
            String first=search(guest,"classic");
            String previous=JsonPath.read(first,"$.room.code");
            leave(host);
            assertEquals("SEARCHING",JsonPath.read(status(guest),"$.status"));
            String newMatch=search(replacement,"classic");
            assertEquals("MATCHED",JsonPath.read(newMatch,"$.status"));
            assertNotEquals(previous,JsonPath.read(newMatch,"$.room.code"));
            assertEquals(guest.id,((Number)JsonPath.read(newMatch,"$.room.players[0].id")).longValue());
            assertEquals(replacement.id,((Number)JsonPath.read(newMatch,"$.room.players[1].id")).longValue());
        } finally {leave(host);leave(guest);leave(replacement);cancel(host);}
    }

    @Test void concurrentPairingUsesEachPlayerAtMostOnce() throws Exception {
        Player first=player(),second=player(),third=player();
        search(first,"classic");
        var executor=Executors.newFixedThreadPool(2);
        var start=new CountDownLatch(1);
        try {
            var calls=java.util.List.of(second,third).stream().map(player->executor.submit(()->{
                start.await();
                return search(player,"classic");
            })).toList();
            start.countDown();
            String a=calls.get(0).get(20,TimeUnit.SECONDS);
            String b=calls.get(1).get(20,TimeUnit.SECONDS);
            assertEquals(Set.of("MATCHED","SEARCHING"),
                Set.of(JsonPath.read(a,"$.status"),JsonPath.read(b,"$.status")));
            assertEquals("MATCHED",JsonPath.read(status(first),"$.status"));
            int waiting=(int)java.util.stream.Stream.of(second,third)
                .filter(player->{
                    try {return "SEARCHING".equals(JsonPath.read(status(player),"$.status"));}
                    catch(Exception ex){throw new IllegalStateException(ex);}
                }).count();
            assertEquals(1,waiting);
        } finally {executor.shutdownNow();leave(first);leave(second);leave(third);cancel(second);cancel(third);}
    }
}
