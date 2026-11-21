package com.FGInteractive.BatalhaNaval.match.mode;

import com.FGInteractive.BatalhaNaval.match.model.Board;
import com.FGInteractive.BatalhaNaval.match.model.Cell;
import java.util.List;

/** Server-side action policy. The browser can only request an id and public targets. */
public interface AbilityRule extends RuleModule {
    @Override default RuleSlot slot() { return RuleSlot.ABILITY; }

    List<AbilitySpec> catalog();

    AbilityOutcome resolve(String abilityId, List<Cell> targets,
                           GameModeDefinition mode, Board defendingBoard);
}
