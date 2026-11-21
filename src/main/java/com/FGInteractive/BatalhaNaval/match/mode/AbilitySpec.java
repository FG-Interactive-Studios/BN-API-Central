package com.FGInteractive.BatalhaNaval.match.mode;

/** Public server-approved targeting metadata, never supplied by the caller. */
public record AbilitySpec(String id, String name, int maxUses, int targetCount,
                          String targetScope, String description) {
    public AbilitySpec {
        if (id == null || !id.matches("[a-z][a-z-]+") || name == null || name.isBlank()
            || maxUses < 1 || targetCount < 0 || targetCount > 4
            || (!"OWN".equals(targetScope) && !"ENEMY".equals(targetScope)
                && !"NONE".equals(targetScope))
            || ("NONE".equals(targetScope) != (targetCount == 0))
            || description == null || description.isBlank())
            throw new IllegalArgumentException("Invalid ability definition");
    }
}
