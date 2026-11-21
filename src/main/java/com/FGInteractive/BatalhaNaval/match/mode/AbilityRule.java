package com.FGInteractive.BatalhaNaval.match.mode;

import com.FGInteractive.BatalhaNaval.match.model.Cell;
import java.util.List;

/** A mode-specific registry of executable abilities. */
public interface AbilityRule extends RuleModule {
    @Override default RuleSlot slot() { return RuleSlot.ABILITY; }
    List<AbilitySpec> catalog();
    AbilityOutcome resolve(String abilityId, List<Cell> targets, AbilityContext context);
}
