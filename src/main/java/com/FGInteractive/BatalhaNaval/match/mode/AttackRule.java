package com.FGInteractive.BatalhaNaval.match.mode;
import com.FGInteractive.BatalhaNaval.match.model.Board;
import com.FGInteractive.BatalhaNaval.match.model.Cell;
import com.FGInteractive.BatalhaNaval.match.model.ShotImpact;
import java.util.Set;

/** Executable action policy; future power attacks can implement this contract. */
public interface AttackRule extends RuleModule {
    @Override default RuleSlot slot() { return RuleSlot.ATTACK; }
    /** incomingShots already contains the new shot when called. */
    ShotImpact resolve(GameModeDefinition mode, Board defendingBoard,
                       Set<Cell> incomingShots, Cell target);
}
