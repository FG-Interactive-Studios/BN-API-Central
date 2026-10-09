package com.FGInteractive.BatalhaNaval.match.mode;
import com.FGInteractive.BatalhaNaval.match.model.ShotImpact;

/** Even a hit spends the turn, unlike some house rules. */
public final class AlternatingTurnRule implements TurnRule {
    @Override public String id() { return "alternating"; }
    @Override public long nextPlayer(long attacker, long defender, ShotImpact impact) {
        return defender;
    }
}
