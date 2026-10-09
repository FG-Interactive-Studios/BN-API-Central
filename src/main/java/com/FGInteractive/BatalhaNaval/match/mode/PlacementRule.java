package com.FGInteractive.BatalhaNaval.match.mode;
import com.FGInteractive.BatalhaNaval.match.dto.PlaceFleetRequest.ShipPlacement;
import com.FGInteractive.BatalhaNaval.match.model.Cell;
import java.util.List;
import java.util.Set;

/** Executable, swappable rule for validating individual ship footprints. */
public interface PlacementRule {
    String id();
    List<Cell> footprint(ShipPlacement placement, ShipDefinition ship, BoardGeometry geometry);
    default void validateAgainstExisting(List<Cell> footprint, Set<Cell> occupied) {
        if (footprint.stream().anyMatch(occupied::contains))
            throw new IllegalArgumentException("Ships cannot overlap");
    }
}
