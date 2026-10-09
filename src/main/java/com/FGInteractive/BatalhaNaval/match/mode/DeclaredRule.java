package com.FGInteractive.BatalhaNaval.match.mode;
public record DeclaredRule(String id, RuleSlot slot) implements RuleModule {
    public DeclaredRule {
        if (id == null || id.isBlank() || slot == null)
            throw new IllegalArgumentException("Invalid rule module");
    }
}
