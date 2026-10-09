package com.FGInteractive.BatalhaNaval.match.mode;

/**
 * Stable composition contract. Gameplay execution methods for these slots
 * are introduced alongside the PR4 action engine; ids alone DO NOT execute powers.
 */
public interface RuleModule {
    String id();
    RuleSlot slot();
    default void validate(GameModeDefinition mode) {}
}
