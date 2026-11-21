package com.FGInteractive.BatalhaNaval.match.mode;

import com.FGInteractive.BatalhaNaval.match.dto.RadarReport;
import com.FGInteractive.BatalhaNaval.match.model.Board;
import com.FGInteractive.BatalhaNaval.match.model.Cell;
import java.util.HashSet;
import java.util.List;

/**
 * Small experimental ruleset for square boards only.
 * Effects are provisional pending approval of the latest Game Design Document.
 * No other geometry silently inherits these behaviors.
 */
public final class SquareAbilityRule implements AbilityRule {
    public static final String DOUBLE_SHOT = "double-shot";
    public static final String RADAR = "radar";
    private static final List<AbilitySpec> CATALOG = List.of(
        new AbilitySpec(DOUBLE_SHOT, "Tiro duplo", 1),
        new AbilitySpec(RADAR, "Radar", 1));

    @Override public String id() { return "square-pilot-v1"; }
    @Override public List<AbilitySpec> catalog() { return CATALOG; }

    @Override public void validate(GameModeDefinition mode) {
        if (!(mode.geometry() instanceof RectangularGeometry geometry)
                || geometry.rows() != geometry.columns())
            throw new IllegalArgumentException("Square abilities require a square rectangular board");
    }

    @Override
    public AbilityOutcome resolve(String abilityId, List<Cell> targets,
                                  GameModeDefinition mode, Board defendingBoard) {
        validate(mode);
        if (abilityId == null || targets == null)
            throw new IllegalArgumentException("Ability and target coordinates are required");
        if (targets.stream().anyMatch(cell -> cell == null || !mode.geometry().contains(cell)))
            throw new IllegalArgumentException("Target is outside the playable board");
        if (new HashSet<>(targets).size() != targets.size())
            throw new IllegalArgumentException("Targets must be distinct");

        if (DOUBLE_SHOT.equals(abilityId)) {
            if (targets.size() != 2)
                throw new IllegalArgumentException("Double shot requires two targets");
            return new AbilityOutcome(targets, null);
        }
        if (RADAR.equals(abilityId)) {
            if (targets.size() != 1)
                throw new IllegalArgumentException("Radar requires a center cell");
            Cell center = targets.get(0);
            // Public contract is an aggregate count, NEVER secret enemy coordinates.
            int detected = (int) defendingBoard.occupied().stream()
                .filter(cell -> Math.abs(cell.row() - center.row()) <= 1
                             && Math.abs(cell.col() - center.col()) <= 1)
                .count();
            return new AbilityOutcome(List.of(),
                new RadarReport(center.row(), center.col(), detected));
        }
        throw new IllegalArgumentException("Unknown ability for this mode");
    }
}
