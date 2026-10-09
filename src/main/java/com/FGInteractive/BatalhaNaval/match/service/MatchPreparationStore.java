package com.FGInteractive.BatalhaNaval.match.service;

import com.FGInteractive.BatalhaNaval.match.model.PreparationRound;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * In-memory state for the academic single-instance MVP.
 * All accesses are made under the matchmaking mutex.
 */
@Component
public class MatchPreparationStore {
    private final Map<String, PreparationRound> rounds = new HashMap<>();

    public void start(String code, long hostId, long guestId) {
        rounds.put(code, new PreparationRound(code, hostId, guestId));
    }

    public PreparationRound get(String code) {
        PreparationRound round = rounds.get(code);
        if (round == null) throw new IllegalStateException("Preparation state unavailable");
        return round;
    }

    public void remove(String code) {
        rounds.remove(code);
    }
}
