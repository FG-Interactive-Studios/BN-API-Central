package com.FGInteractive.BatalhaNaval.match.mode;

import java.util.List;

/** Immutable action plan. Validation completes before the shared match state is mutated. */
public record AbilityOutcome(List<AbilityEffect> effects) {
    public AbilityOutcome {
        if (effects == null || effects.isEmpty())
            throw new IllegalArgumentException("Ability needs an effect");
        effects = List.copyOf(effects);
    }
    public static AbilityOutcome of(AbilityEffect effect) { return new AbilityOutcome(List.of(effect)); }
}
