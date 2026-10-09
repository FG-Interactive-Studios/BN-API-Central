package com.FGInteractive.BatalhaNaval.match.mode;
import com.FGInteractive.BatalhaNaval.match.dto.PlaceFleetRequest.ShipPlacement;
import com.FGInteractive.BatalhaNaval.match.model.Cell;
import com.FGInteractive.BatalhaNaval.match.model.Orientation;
import java.util.ArrayList;
import java.util.List;

/** Horizontal or vertical straight ships, including on irregular playable masks. */
public final class LinearPlacementRule implements PlacementRule {
    @Override public String id() { return "linear"; }
    @Override public List<Cell> footprint(ShipPlacement placement, ShipDefinition ship,
                                           BoardGeometry geometry) {
        if (placement == null || placement.orientation() == null
            || placement.row() == null || placement.col() == null)
            throw new IllegalArgumentException("Ship coordinates and orientation are required");
        List<Cell> cells = new ArrayList<>(ship.length());
        for (int i = 0; i < ship.length(); i++) {
            Cell cell = new Cell(placement.row() +
                (placement.orientation() == Orientation.VERTICAL ? i : 0),
                placement.col() + (placement.orientation() == Orientation.HORIZONTAL ? i : 0));
            if (!geometry.contains(cell)) {
                throw new IllegalArgumentException("Ship is outside the playable board");
            }
            cells.add(cell);
        }
        return List.copyOf(cells);
    }
}
