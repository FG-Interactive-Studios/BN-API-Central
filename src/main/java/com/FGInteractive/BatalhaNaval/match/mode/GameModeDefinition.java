package com.FGInteractive.BatalhaNaval.match.mode;
import java.util.*;

/**
 * Immutable snapshot of a game mode; only the backend selects executable rules.
 * Future gameplay action modules must implement semantics, not accept arbitrary
 * client-selected rule names or power code.
 */
public record GameModeDefinition(
    String id, String name, BoardGeometry geometry, FleetDefinition fleet,
    PlacementRule placement, Map<RuleSlot, RuleModule> rules
) {
    public GameModeDefinition {
        if (id == null || !id.matches("[a-z][a-z0-9-]{1,31}") || name == null
            || name.isBlank() || geometry == null || fleet == null || placement == null
            || rules == null) throw new IllegalArgumentException("Invalid game mode");
        rules = Map.copyOf(rules);
        if (rules.size() != RuleSlot.values().length)
            throw new IllegalArgumentException("Each rule slot needs one implementation");
        for (RuleSlot slot : RuleSlot.values()) {
            RuleModule rule = rules.get(slot);
            if (rule == null || rule.slot() != slot)
                throw new IllegalArgumentException("Missing or mismatched rule slot: "+slot);
        }
        if (fleet.occupiedCells() > geometry.cells().size())
            throw new IllegalArgumentException("Fleet exceeds playable board capacity");
    }

    public ModeSummary summary() {
        return new ModeSummary(id, name, new ModeSummary.GeometryInfo(
            geometry.kind(), geometry.rows(), geometry.columns(), geometry.cells()),
            fleet.ships(), placement.id(), rules.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                    e -> e.getKey().name(), e -> e.getValue().id())));
    }
}
