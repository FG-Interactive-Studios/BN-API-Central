package com.FGInteractive.BatalhaNaval.store.controller;

import com.FGInteractive.BatalhaNaval.match.service.BattleService;
import com.FGInteractive.BatalhaNaval.store.service.StoreService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Credit claims are validated against the authenticated user's current finished battle. */
@RestController
@RequestMapping("/api/store/rewards")
public class VirtualRewardController {
    private final StoreService store;
    private final BattleService battle;
    public VirtualRewardController(StoreService store,BattleService battle) {
        this.store=store;this.battle=battle;
    }
    @PostMapping("/claim")
    public StoreService.RewardReceipt claim(@AuthenticationPrincipal Jwt jwt) {
        long userId=Long.parseLong(jwt.getSubject());
        var result=battle.mine(userId); // JWT-scoped: no supplied matchId/winner.
        if(!"FINISHED".equals(result.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Finish the battle to earn virtual credits");
        return store.claimVirtualMatchReward(userId,result.matchId(),result.winnerId());
    }
}
