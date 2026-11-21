package com.FGInteractive.BatalhaNaval.match.mode;

/** Public metadata. Limit is enforced per player and per battle, in memory. */
public record AbilitySpec(String id, String name, int maxUses) {
    public AbilitySpec {
        if (id == null || id.isBlank() || name == null || name.isBlank() || maxUses < 1)
            throw new IllegalArgumentException("Invalid ability definition");
    }
}
