package com.FGInteractive.BatalhaNaval.match.service;

import com.FGInteractive.BatalhaNaval.match.model.PreparationRound;
import com.FGInteractive.BatalhaNaval.match.mode.GameModeDefinition;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** In-memory rounds. All operations run under MatchmakingService's mutex. */
@Component
public class MatchPreparationStore {
    private final Map<String, PreparationRound> rounds = new HashMap<>();

    public void start(String code, long hostId, long guestId, GameModeDefinition mode) {
        rounds.put(code, new PreparationRound(code, hostId, guestId, mode));
    }
    public PreparationRound get(String code) {
        PreparationRound round = rounds.get(code);
        if (round == null) throw new IllegalStateException("Preparation state unavailable");
        return round;
    }
    public void remove(String code) { rounds.remove(code); }
}
