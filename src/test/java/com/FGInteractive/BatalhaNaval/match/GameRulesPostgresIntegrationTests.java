package com.FGInteractive.BatalhaNaval.match;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.FGInteractive.BatalhaNaval.matchmaking.service.MatchmakingService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Validate the production GDD clock setting through the actual REST and lobby wiring. */
@SpringBootTest(properties="match.gdd-enforced=true")
@AutoConfigureMockMvc
@ActiveProfiles("ci")
class GameRulesPostgresIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired MatchmakingService matchmaking;

    private record Player(String jwt) {}
    private Player player() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        String email="timed-"+suffix+"@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"nickname":"Timed%s","email":"%s","password":"safe-password-123"}
                """.formatted(suffix,email)))
            .andExpect(status().isCreated());
        String json=mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"safe-password-123"}
                """.formatted(email)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Player(JsonPath.read(json,"$.accessToken"));
    }
    private void leave(Player player) throws Exception {
        mvc.perform(delete("/api/matchmaking/lobbies/me")
            .header("Authorization","Bearer "+player.jwt)).andExpect(status().isNoContent());
    }

    @Test void timedPreparationAutoStartsAndKeepsEachPlayersShipsPrivate() throws Exception {
        Player host=player(),guest=player();
        try {
            String lobby=mvc.perform(post("/api/matchmaking/lobbies")
                .header("Authorization","Bearer "+host.jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"modeId\":\"classic-powers\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
            String code=JsonPath.read(lobby,"$.code");
            mvc.perform(post("/api/matchmaking/lobbies/join")
                .header("Authorization","Bearer "+guest.jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\""+code+"\"}")).andExpect(status().isOk());
            for(Player player:new Player[]{host,guest}) {
                mvc.perform(put("/api/matchmaking/lobbies/me/ready")
                    .header("Authorization","Bearer "+player.jwt)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"ready\":true}"))
                    .andExpect(status().isOk());
            }
            String own=mvc.perform(get("/api/matches/me/placement")
                .header("Authorization","Bearer "+host.jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            Instant expiration=Instant.parse(JsonPath.read(own,"$.placementDeadlineAt"));
            assertEquals(0,((Number)JsonPath.read(own,"$.yourShips.length()")).intValue());
            matchmaking.sweepGameRulesAt(expiration);
            mvc.perform(get("/api/matchmaking/lobbies/me")
                .header("Authorization","Bearer "+host.jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLAYING"));
            for(Player participant:new Player[]{host,guest}) {
                mvc.perform(get("/api/matches/me/placement")
                    .header("Authorization","Bearer "+participant.jwt))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fleetConfirmed").value(true))
                    .andExpect(jsonPath("$.yourShips.length()").value(3));
                mvc.perform(get("/api/matches/me")
                    .header("Authorization","Bearer "+participant.jwt))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PLAYING"))
                    .andExpect(jsonPath("$.yourAbilityPointCells.length()").value(4))
                    .andExpect(jsonPath("$.yourAbilityPointsEarned").value(0))
                    .andExpect(jsonPath("$.turnDeadlineAt").isNotEmpty())
                    .andExpect(jsonPath("$.matchDeadlineAt").isNotEmpty());
            }
        } finally {
            leave(host);
            leave(host);
            leave(guest);
        }
    }
}
