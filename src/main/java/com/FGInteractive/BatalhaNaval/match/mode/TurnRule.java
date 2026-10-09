package com.FGInteractive.BatalhaNaval.match.mode;
import com.FGInteractive.BatalhaNaval.match.model.ShotImpact;

/** Turn progression after a successful validated action. */
public interface TurnRule extends RuleModule {
    @Override default RuleSlot slot() { return RuleSlot.TURN; }
    long nextPlayer(long attacker, long defender, ShotImpact impact);
}
