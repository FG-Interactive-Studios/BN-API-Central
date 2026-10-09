package com.FGInteractive.BatalhaNaval.match.mode;
import com.FGInteractive.BatalhaNaval.match.model.Board;
import com.FGInteractive.BatalhaNaval.match.model.Cell;
import java.util.Set;

/** Victory condition evaluated only after valid attacks. */
public interface VictoryRule extends RuleModule {
    @Override default RuleSlot slot() { return RuleSlot.VICTORY; }
    boolean defeated(Board defender, Set<Cell> incomingShots);
}
