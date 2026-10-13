package com.FGInteractive.BatalhaNaval.match.mode;

import com.FGInteractive.BatalhaNaval.match.dto.PlaceFleetRequest.ShipPlacement;
import com.FGInteractive.BatalhaNaval.match.model.Cell;
import com.FGInteractive.BatalhaNaval.match.model.Orientation;
import java.util.ArrayList;
import java.util.List;

/**
 * Hex lattice has three undirected straight axes: vertical and the two 60°
 * diagonals. Every consecutive ship segment shares a HEXAGON edge.
 */
public final class HexagonalPlacementRule implements PlacementRule {
    private static final List<Orientation> DIRECTIONS = List.of(
        Orientation.HEX_VERTICAL, Orientation.HEX_DIAGONAL_UP,
        Orientation.HEX_DIAGONAL_DOWN);
    @Override public String id() { return "hexagonal-straight"; }
    @Override public List<Orientation> directions() { return DIRECTIONS; }
    @Override public boolean supports(BoardGeometry geometry) {
        return geometry instanceof HexagonalGeometry;
    }
    @Override public List<Cell> footprint(ShipPlacement placement, ShipDefinition ship,
                                           BoardGeometry geometry) {
        if (!supports(geometry) || placement == null || placement.row() == null
            || placement.col() == null || !DIRECTIONS.contains(placement.orientation()))
            throw new IllegalArgumentException("Invalid hexagonal ship placement");
        int dr = switch (placement.orientation()) {
            case HEX_VERTICAL -> 1;
            case HEX_DIAGONAL_UP -> -1;
            case HEX_DIAGONAL_DOWN -> 0;
            default -> throw new IllegalArgumentException("Invalid hexagonal direction");
        };
        int dc = placement.orientation() == Orientation.HEX_VERTICAL ? 0 : 1;
        List<Cell> result = new ArrayList<>(ship.length());
        for (int i=0;i<ship.length();i++) {
            Cell cell = new Cell(placement.row()+i*dr,placement.col()+i*dc);
            if (!geometry.contains(cell))
                throw new IllegalArgumentException("Ship is outside the hexagonal board");
            result.add(cell);
        }
        return List.copyOf(result);
    }
}
