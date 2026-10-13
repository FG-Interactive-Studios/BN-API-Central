package com.FGInteractive.BatalhaNaval.match.mode;

import com.FGInteractive.BatalhaNaval.match.model.Cell;
import java.util.ArrayList;
import java.util.List;

/**
 * Regular octagons arranged on a square lattice. Regular octagons cannot
 * tile alone: the small interstitial diamonds are intentionally NOT cells.
 * Orthogonal neighbors share edges; diagonals align their centers across gaps.
 */
public record OctagonalGeometry(int rows, int columns) implements BoardGeometry {
    // Makes long horizontal/vertical edges equal to the 45-degree cut edges.
    private static final double CUT = 1.0 / (2.0 + Math.sqrt(2.0));

    public OctagonalGeometry {
        if (rows < 3 || columns < 3 || rows > 20 || columns > 20)
            throw new IllegalArgumentException("Octagonal board dimensions must be 3..20");
    }
    @Override public String kind() { return "OCTAGONAL_TILES"; }
    @Override public boolean contains(Cell c) {
        return c != null && c.row() >= 0 && c.col() >= 0
            && c.row() < rows && c.col() < columns;
    }
    // The default BoardGeometry.neighbors is correct: four edge neighbors.
    // Diagonal placement is still legal across non-playable small gaps.
    @Override public List<TileShape> tiles() {
        List<TileShape> result = new ArrayList<>();
        for (Cell cell : cells()) {
            double x = cell.col(), y = cell.row(), d = CUT;
            result.add(new TileShape(cell,"OCTAGON",List.of(
                new TileShape.Point(x+d,y),
                new TileShape.Point(x+1-d,y),
                new TileShape.Point(x+1,y+d),
                new TileShape.Point(x+1,y+1-d),
                new TileShape.Point(x+1-d,y+1),
                new TileShape.Point(x+d,y+1),
                new TileShape.Point(x,y+1-d),
                new TileShape.Point(x,y+d)
            )));
        }
        return List.copyOf(result);
    }
}
