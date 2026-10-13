package com.FGInteractive.BatalhaNaval.match.mode;

import com.FGInteractive.BatalhaNaval.match.dto.PlaceFleetRequest.ShipPlacement;
import com.FGInteractive.BatalhaNaval.match.model.Cell;
import com.FGInteractive.BatalhaNaval.match.model.Orientation;
import java.util.ArrayList;
import java.util.List;

/**
 * Three zigzagging edge-connected axes of an equilateral triangle tiling.
 * Not interchangeable with a square-grid "horizontal/vertical" footprint.
 */
public final class TriangularEdgePlacementRule implements PlacementRule {
    private static final List<Orientation> AXES = List.of(
        Orientation.TRI_RIGHT, Orientation.TRI_DOWN_LEFT, Orientation.TRI_DOWN_RIGHT);

    @Override public String id() { return "triangle-edge-path"; }
    @Override public List<Orientation> directions() { return AXES; }
    @Override public boolean supports(BoardGeometry geometry) {
        return geometry instanceof TriangularTileGeometry;
    }

    @Override
    public List<Cell> footprint(ShipPlacement placement, ShipDefinition ship,
                                BoardGeometry geometry) {
        if (!supports(geometry))
            throw new IllegalArgumentException("Triangle placement requires triangular tiles");
        if (placement == null || placement.row() == null || placement.col() == null
            || placement.orientation() == null || !AXES.contains(placement.orientation()))
            throw new IllegalArgumentException("Invalid triangular ship placement");
        Cell current = new Cell(placement.row(), placement.col());
        List<Cell> footprint = new ArrayList<>(ship.length());
        for (int i = 0; i < ship.length(); i++) {
            if (!geometry.contains(current))
                throw new IllegalArgumentException("Ship is outside triangular board");
            footprint.add(current);
            if (i + 1 == ship.length()) break;
            boolean upward = current.col() % 2 == 0;
            Cell next = switch (placement.orientation()) {
                case TRI_RIGHT -> new Cell(current.row(), current.col() + 1);
                case TRI_DOWN_RIGHT -> upward
                    ? new Cell(current.row() + 1, current.col() + 1)
                    : new Cell(current.row(), current.col() + 1);
                case TRI_DOWN_LEFT -> upward
                    ? new Cell(current.row() + 1, current.col() + 1)
                    : new Cell(current.row(), current.col() - 1);
                default -> throw new IllegalArgumentException("Unsupported triangle axis");
            };
            if (!geometry.neighbors(current).contains(next))
                throw new IllegalArgumentException("Ship segments must share a triangle edge");
            current = next;
        }
        return List.copyOf(footprint);
    }
}
