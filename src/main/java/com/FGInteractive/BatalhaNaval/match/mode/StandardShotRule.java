package com.FGInteractive.BatalhaNaval.match.mode;
import com.FGInteractive.BatalhaNaval.match.dto.PlaceFleetRequest.ShipPlacement;
import com.FGInteractive.BatalhaNaval.match.model.Board;
import com.FGInteractive.BatalhaNaval.match.model.Cell;
import com.FGInteractive.BatalhaNaval.match.model.ShotImpact;
import java.util.HashSet;
import java.util.Set;

/** Ordinary one-cell shot: no enemy positions are returned on a non-sinking hit. */
public final class StandardShotRule implements AttackRule {
    @Override public String id() { return "standard-shot"; }

    @Override
    public ShotImpact resolve(GameModeDefinition mode, Board defendingBoard,
                              Set<Cell> incomingShots, Cell target) {
        if (!defendingBoard.occupied().contains(target)) return new ShotImpact(false, null);
        for (ShipPlacement ship : defendingBoard.ships()) {
            Set<Cell> footprint = new HashSet<>(mode.placement().footprint(
                ship, mode.fleet().require(ship.type()), mode.geometry()));
            if (footprint.contains(target) && incomingShots.containsAll(footprint))
                return new ShotImpact(true, ship.type());
        }
        return new ShotImpact(true, null);
    }
}
