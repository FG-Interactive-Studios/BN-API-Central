package com.FGInteractive.BatalhaNaval.store;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
class VirtualEconomyAndTauntsPostgresIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    private record Player(long id,String token) {}
    private Player player() throws Exception {
        String name=UUID.randomUUID().toString().substring(0,8);
        String email="chaos-"+name+"@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"nickname\":\"Chaos"+name+"\",\"email\":\""+email+"\",\"password\":\"safe-password-123\"}"))
            .andExpect(status().isCreated());
        String json=mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\""+email+"\",\"password\":\"safe-password-123\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Player(((Number)JsonPath.read(json,"$.user.id")).longValue(),
            (String)JsonPath.read(json,"$.accessToken"));
    }
    private String catalog(Player p) throws Exception {
        return mvc.perform(get("/api/store").header("Authorization","Bearer "+p.token))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    private String buy(Player p,String id) throws Exception {
        return mvc.perform(post("/api/store/purchase/{id}",id)
            .header("Authorization","Bearer "+p.token))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    private String equip(Player p,String id) throws Exception {
        return mvc.perform(put("/api/store/equip/{id}",id)
            .header("Authorization","Bearer "+p.token))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    private void startBattle(Player a,Player b) throws Exception {
        for(Player p:new Player[]{a,b})
            mvc.perform(post("/api/matchmaking/queue")
                .header("Authorization","Bearer "+p.token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"modeId\":\"classic\"}")).andExpect(status().isOk());
        for(Player p:new Player[]{a,b})
            mvc.perform(put("/api/matchmaking/lobbies/me/ready")
                .header("Authorization","Bearer "+p.token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ready\":true}")).andExpect(status().isOk());
        for(Player p:new Player[]{a,b}){
            mvc.perform(post("/api/matches/me/placement/random")
                .header("Authorization","Bearer "+p.token)).andExpect(status().isOk());
            mvc.perform(post("/api/matches/me/placement/confirm")
                .header("Authorization","Bearer "+p.token)).andExpect(status().isOk());
        }
    }
    private void leave(Player p) throws Exception {
        mvc.perform(delete("/api/matchmaking/lobbies/me")
            .header("Authorization","Bearer "+p.token)).andExpect(status().isNoContent());
    }

    @Test void purchaseEquipSendAndClaimFakeCoinsWithNoDoubleRewards() throws Exception {
        Player a=player(),b=player();
        try {
            String original=catalog(a);
            assertEquals(300,((Number)JsonPath.read(original,"$.credits")).intValue());
            assertEquals(true,JsonPath.read(original,
                "$.items[?(@.id == 'taunt_default')].equipped").toString().contains("true"));
            assertEquals(false,JsonPath.read(original,
                "$.items[?(@.id == 'taunt_banter')].owned").toString().contains("true"));
            catalog(b);
            String bought=buy(a,"taunt_banter");
            assertEquals(210,((Number)JsonPath.read(bought,"$.credits")).intValue());
            assertEquals(true,JsonPath.read(equip(a,"taunt_banter"),
                "$.items[?(@.id == 'taunt_banter')].equipped").toString().contains("true"));
            mvc.perform(post("/api/store/purchase/taunt_banter")
                .header("Authorization","Bearer "+a.token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_OWNED"));
            mvc.perform(put("/api/store/equip/taunt_panic")
                .header("Authorization","Bearer "+b.token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_OWNED"));
            mvc.perform(post("/api/matches/me/taunts")
                .header("Authorization","Bearer "+a.token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"index\":0}"))
                .andExpect(status().isNotFound());
            startBattle(a,b);
            mvc.perform(post("/api/store/rewards/claim")
                .header("Authorization","Bearer "+a.token)).andExpect(status().isConflict());
            String taunt=mvc.perform(post("/api/matches/me/taunts")
                .header("Authorization","Bearer "+a.token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"index\":0}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertEquals("taunt_banter",JsonPath.read(taunt,"$.packId"));
            assertEquals("É pavê ou pra afundar? 🍮",JsonPath.read(taunt,"$.message"));
            assertEquals(a.id(),((Number)JsonPath.read(taunt,"$.senderId")).longValue());
            mvc.perform(post("/api/matches/me/taunts")
                .header("Authorization","Bearer "+a.token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"index\":1}"))
                .andExpect(status().isTooManyRequests());
            mvc.perform(post("/api/matches/me/taunts")
                .header("Authorization","Bearer "+b.token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"index\":4}"))
                .andExpect(status().isBadRequest());
            mvc.perform(post("/api/matches/me/forfeit")
                .header("Authorization","Bearer "+b.token)).andExpect(status().isOk());
            String victory=mvc.perform(post("/api/store/rewards/claim")
                .header("Authorization","Bearer "+a.token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertEquals(60,((Number)JsonPath.read(victory,"$.earned")).intValue());
            assertEquals(270,((Number)JsonPath.read(victory,"$.wallet.credits")).intValue());
            String again=mvc.perform(post("/api/store/rewards/claim")
                .header("Authorization","Bearer "+a.token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertEquals(0,((Number)JsonPath.read(again,"$.earned")).intValue());
            assertEquals(270,((Number)JsonPath.read(again,"$.wallet.credits")).intValue());
            String defeated=mvc.perform(post("/api/store/rewards/claim")
                .header("Authorization","Bearer "+b.token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertEquals(25,((Number)JsonPath.read(defeated,"$.earned")).intValue());
            assertEquals(325,((Number)JsonPath.read(defeated,"$.wallet.credits")).intValue());
            // No cross-account access to the other's wallet or rewards.
            assertEquals(1,jdbc.queryForObject(
                "SELECT count(*) FROM virtual_match_rewards WHERE user_id=?",Integer.class,a.id()));
        } finally {leave(a);leave(b);}
    }

    @Test void anonymousCannotBuyTauntsOrClaimUnfinishedRewards() throws Exception {
        mvc.perform(post("/api/store/purchase/taunt_banter")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/store/equip/taunt_banter")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/matches/me/taunts").contentType(MediaType.APPLICATION_JSON)
            .content("{\"index\":0}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/store/rewards/claim")).andExpect(status().isUnauthorized());
    }
}
