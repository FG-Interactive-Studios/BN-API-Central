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
class RematchPostgresIntegrationTests {
    @Autowired MockMvc mvc;
    private record Player(long id,String jwt) {}

    private Player player() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        String email="rematch-"+suffix+"@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"nickname\":\"Crew"+suffix+"\",\"email\":\""+email+"\",\"password\":\"safe-password-123\"}"))
            .andExpect(status().isCreated());
        String response=mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\""+email+"\",\"password\":\"safe-password-123\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Player(((Number)JsonPath.read(response,"$.user.id")).longValue(),
            JsonPath.read(response,"$.accessToken"));
    }
    private String response(Player player,String url) throws Exception {
        return mvc.perform(get(url).header("Authorization","Bearer "+player.jwt))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    private String vote(Player player,boolean accepted) throws Exception {
        return mvc.perform(put("/api/matchmaking/lobbies/me/rematch")
            .header("Authorization","Bearer "+player.jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accepted\":"+accepted+"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    private void ready(Player player) throws Exception {
        mvc.perform(put("/api/matchmaking/lobbies/me/ready")
            .header("Authorization","Bearer "+player.jwt)
            .contentType(MediaType.APPLICATION_JSON).content("{\"ready\":true}"))
            .andExpect(status().isOk());
    }
    private void leave(Player player) throws Exception {
        mvc.perform(delete("/api/matchmaking/lobbies/me")
            .header("Authorization","Bearer "+player.jwt))
            .andExpect(status().isNoContent());
    }
    private String setup(Player host,Player guest,boolean publicRoom) throws Exception {
        if(publicRoom){
            mvc.perform(post("/api/matchmaking/queue").header("Authorization","Bearer "+host.jwt)
                .contentType(MediaType.APPLICATION_JSON).content("{\"modeId\":\"classic\"}"))
                .andExpect(status().isOk());
            mvc.perform(post("/api/matchmaking/queue").header("Authorization","Bearer "+guest.jwt)
                .contentType(MediaType.APPLICATION_JSON).content("{\"modeId\":\"classic\"}"))
                .andExpect(status().isOk());
        } else {
            String created=mvc.perform(post("/api/matchmaking/lobbies")
                .header("Authorization","Bearer "+host.jwt)
                .contentType(MediaType.APPLICATION_JSON).content("{\"modeId\":\"classic\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            String code=JsonPath.read(created,"$.code");
            mvc.perform(post("/api/matchmaking/lobbies/join")
                .header("Authorization","Bearer "+guest.jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\""+code+"\"}")).andExpect(status().isOk());
        }
        String original=response(host,"/api/matchmaking/lobbies/me");
        ready(host);ready(guest);
        for(Player player:new Player[]{host,guest}) {
            mvc.perform(post("/api/matches/me/placement/random")
                .header("Authorization","Bearer "+player.jwt)).andExpect(status().isOk());
            mvc.perform(post("/api/matches/me/placement/confirm")
                .header("Authorization","Bearer "+player.jwt)).andExpect(status().isOk());
        }
        assertEquals("PLAYING",JsonPath.read(response(host,"/api/matchmaking/lobbies/me"),"$.status"));
        mvc.perform(post("/api/matches/me/forfeit")
            .header("Authorization","Bearer "+guest.jwt)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FINISHED"));
        return original;
    }

    @Test void rematchRequiresTwoVotesAndGivesFreshFleetAndMatchWithoutQueue() throws Exception {
        Player host=player(),guest=player(),outsider=player();
        try {
            String initial=setup(host,guest,false);
            String oldCode=JsonPath.read(initial,"$.code");
            String oldMatch=JsonPath.read(response(host,"/api/matches/me"),"$.matchId");
            mvc.perform(put("/api/matchmaking/lobbies/me/rematch")
                .contentType(MediaType.APPLICATION_JSON).content("{\"accepted\":true}"))
                .andExpect(status().isUnauthorized());
            mvc.perform(put("/api/matchmaking/lobbies/me/rematch")
                .header("Authorization","Bearer "+outsider.jwt)
                .contentType(MediaType.APPLICATION_JSON).content("{\"accepted\":true}"))
                .andExpect(status().isNotFound());
            mvc.perform(put("/api/matchmaking/lobbies/me/rematch")
                .header("Authorization","Bearer "+host.jwt)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
            String first=vote(host,true);
            assertEquals("FINISHED",JsonPath.read(first,"$.status"));
            assertEquals(true,JsonPath.read(first,"$.rematch.hostAccepted"));
            assertEquals(false,JsonPath.read(first,"$.rematch.guestAccepted"));
            assertEquals(true,JsonPath.read(first,"$.rematch.available"));
            String same=vote(host,true);
            assertEquals(((Number)JsonPath.read(first,"$.revision")).longValue(),
                ((Number)JsonPath.read(same,"$.revision")).longValue(),
                "Repeated vote must be idempotent");
            String withdrawn=vote(host,false);
            assertEquals(false,JsonPath.read(withdrawn,"$.rematch.hostAccepted"));
            vote(host,true);
            String newRoom=vote(guest,true);
            assertEquals("PREPARING",JsonPath.read(newRoom,"$.status"));
            assertEquals(false,JsonPath.read(newRoom,"$.publicMatch"));
            assertEquals("classic",JsonPath.read(newRoom,"$.mode.id"));
            assertNotEquals(oldCode,(String)JsonPath.read(newRoom,"$.code"));
            assertEquals(host.id,((Number)JsonPath.read(newRoom,"$.players[0].id")).longValue());
            assertEquals(guest.id,((Number)JsonPath.read(newRoom,"$.players[1].id")).longValue());
            assertNull(JsonPath.read(newRoom,"$.rematch"));
            String fresh=response(host,"/api/matches/me/placement");
            assertEquals(0,((Number)JsonPath.read(fresh,"$.yourShips.length()")).intValue());
            assertEquals(false,JsonPath.read(fresh,"$.fleetConfirmed"));
            assertEquals(false,JsonPath.read(response(guest,"/api/matches/me/placement"),"$.fleetConfirmed"));
            mvc.perform(put("/api/matchmaking/lobbies/me/rematch")
                .header("Authorization","Bearer "+host.jwt)
                .contentType(MediaType.APPLICATION_JSON).content("{\"accepted\":true}"))
                .andExpect(status().isConflict());
            // New session is a genuine battle, with a distinct matchId.
            for(Player player:new Player[]{host,guest}){
                mvc.perform(post("/api/matches/me/placement/random")
                    .header("Authorization","Bearer "+player.jwt)).andExpect(status().isOk());
                mvc.perform(post("/api/matches/me/placement/confirm")
                    .header("Authorization","Bearer "+player.jwt)).andExpect(status().isOk());
            }
            assertNotEquals(oldMatch,(String)JsonPath.read(response(host,"/api/matches/me"),"$.matchId"));
            mvc.perform(get("/api/matchmaking/state").header("Authorization","Bearer "+guest.jwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("MATCHED"));
        } finally {leave(host);leave(guest);leave(outsider);}
    }

    @Test void publicMatchRematchStaysPairedAndOpponentCanDeclineByLeaving() throws Exception {
        Player host=player(),guest=player();
        try {
            setup(host,guest,true);
            String asked=vote(host,true);
            assertEquals(true,JsonPath.read(asked,"$.publicMatch"));
            assertEquals(true,JsonPath.read(asked,"$.rematch.hostAccepted"));
            leave(guest);
            String refused=response(host,"/api/matchmaking/lobbies/me");
            assertEquals("FINISHED",JsonPath.read(refused,"$.status"));
            assertEquals(false,JsonPath.read(refused,"$.rematch.available"));
            assertEquals(true,JsonPath.read(refused,"$.rematch.declined"));
            mvc.perform(put("/api/matchmaking/lobbies/me/rematch")
                .header("Authorization","Bearer "+host.jwt)
                .contentType(MediaType.APPLICATION_JSON).content("{\"accepted\":true}"))
                .andExpect(status().isConflict());
            mvc.perform(get("/api/matchmaking/state").header("Authorization","Bearer "+guest.jwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IDLE"));
            // A different public opponent must not be matched through rematch.
            mvc.perform(get("/api/matchmaking/queue/me").header("Authorization","Bearer "+host.jwt))
                .andExpect(status().isNotFound());
        } finally {leave(host);leave(guest);}
    }

    @Test void simultaneousAcceptanceCreatesExactlyOneRoom() throws Exception {
        Player a=player(),b=player();
        setup(a,b,true);
        var executor=Executors.newFixedThreadPool(2);
        var gate=new CountDownLatch(1);
        try {
            var first=executor.submit(()->{gate.await();return vote(a,true);});
            var second=executor.submit(()->{gate.await();return vote(b,true);});
            gate.countDown();
            String one=first.get(20,TimeUnit.SECONDS),two=second.get(20,TimeUnit.SECONDS);
            assertEquals(Set.of("FINISHED","PREPARING"),
                Set.of(JsonPath.read(one,"$.status"),JsonPath.read(two,"$.status")));
            String roomA=response(a,"/api/matchmaking/lobbies/me");
            String roomB=response(b,"/api/matchmaking/lobbies/me");
            assertEquals("PREPARING",JsonPath.read(roomA,"$.status"));
            assertEquals((String)JsonPath.read(roomA,"$.code"),(String)JsonPath.read(roomB,"$.code"));
            assertEquals(true,JsonPath.read(roomA,"$.publicMatch"));
            assertNotNull(JsonPath.read(response(a,"/api/matches/me/placement"),"$.preparationId"));
        } finally {executor.shutdownNow();leave(a);leave(b);}
    }
}
