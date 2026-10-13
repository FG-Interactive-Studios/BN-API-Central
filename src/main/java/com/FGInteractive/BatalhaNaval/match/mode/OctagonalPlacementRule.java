package com.FGInteractive.BatalhaNaval.match.mode;

import com.FGInteractive.BatalhaNaval.match.dto.PlaceFleetRequest.ShipPlacement;
import com.FGInteractive.BatalhaNaval.match.model.Cell;
import com.FGInteractive.BatalhaNaval.match.model.Orientation;
import java.util.ArrayList;
import java.util.List;

/**
 * Horizontal, vertical and both diagonal straight axes. Diagonal octagons
 * are aligned center-to-center across small empty square gaps. Those gaps
 * cannot be attacked; every octagon remains one independently targetable cell.
 */
public final class OctagonalPlacementRule implements PlacementRule {
    private static final List<Orientation> DIRECTIONS = List.of(
        Orientation.HORIZONTAL, Orientation.VERTICAL,
        Orientation.DIAGONAL_DOWN, Orientation.DIAGONAL_UP);
    @Override public String id() { return "octagonal-straight"; }
    @Override public List<Orientation> directions() { return DIRECTIONS; }
    @Override public boolean supports(BoardGeometry geometry) {
        return geometry instanceof OctagonalGeometry;
    }
    @Override public List<Cell> footprint(ShipPlacement placement, ShipDefinition ship,
                                           BoardGeometry geometry) {
        if (!supports(geometry) || placement == null || placement.row() == null
            || placement.col() == null || !DIRECTIONS.contains(placement.orientation()))
            throw new IllegalArgumentException("Invalid octagonal ship placement");
        int dr = switch (placement.orientation()) {
            case VERTICAL, DIAGONAL_DOWN -> 1;
            case DIAGONAL_UP -> -1;
            case HORIZONTAL -> 0;
            default -> throw new IllegalArgumentException("Invalid octagonal direction");
        };
        int dc = placement.orientation() == Orientation.VERTICAL ? 0 : 1;
        List<Cell> result = new ArrayList<>(ship.length());
        for (int i=0;i<ship.length();i++) {
            Cell cell = new Cell(placement.row()+i*dr,placement.col()+i*dc);
            if (!geometry.contains(cell))
                throw new IllegalArgumentException("Ship is outside the octagonal board");
            result.add(cell);
        }
        return List.copyOf(result);
    }
}
