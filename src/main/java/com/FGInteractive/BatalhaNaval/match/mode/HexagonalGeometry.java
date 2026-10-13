package com.FGInteractive.BatalhaNaval.match.mode;

import com.FGInteractive.BatalhaNaval.match.model.Cell;
import java.util.ArrayList;
import java.util.List;

/**
 * Flat-topped regular hexagon cells in a radius-R hex-shaped board.
 * Coordinates are shifted axial coordinates: q=col-radius, r=row-radius,
 * s=-q-r. Neighbors share an entire hexagon edge.
 */
public record HexagonalGeometry(int radius) implements BoardGeometry {
    private static final double SQRT3 = Math.sqrt(3.0);

    public HexagonalGeometry {
        if (radius < 2 || radius > 10)
            throw new IllegalArgumentException("Hex radius must be between 2 and 10");
    }
    @Override public String kind() { return "HEXAGONAL_TILES"; }
    @Override public int rows() { return 2 * radius + 1; }
    @Override public int columns() { return 2 * radius + 1; }

    @Override public boolean contains(Cell cell) {
        if (cell == null) return false;
        int q = cell.col() - radius;
        int r = cell.row() - radius;
        return Math.abs(q) <= radius && Math.abs(r) <= radius
            && Math.abs(q + r) <= radius;
    }

    @Override public List<Cell> neighbors(Cell cell) {
        if (!contains(cell)) return List.of();
        List<Cell> result = new ArrayList<>(6);
        for (int[] delta : new int[][]{{1,0},{-1,0},{0,1},{0,-1},{-1,1},{1,-1}}) {
            Cell next = new Cell(cell.row() + delta[0], cell.col() + delta[1]);
            if (contains(next)) result.add(next);
        }
        return List.copyOf(result);
    }

    @Override public List<TileShape> tiles() {
        List<TileShape> result = new ArrayList<>();
        for (Cell cell : cells()) {
            // 1.5 units between neighboring columns, sqrt(3) along rows.
            double cx = 1 + 1.5 * cell.col();
            double cy = 1 + SQRT3 * (cell.row() + cell.col() / 2.0);
            List<TileShape.Point> polygon = new ArrayList<>(6);
            for (int i = 0; i < 6; i++) {
                double angle = i * Math.PI / 3;
                polygon.add(new TileShape.Point(cx + Math.cos(angle),
                    cy + Math.sin(angle)));
            }
            result.add(new TileShape(cell,"HEXAGON",polygon));
        }
        return List.copyOf(result);
    }
}
