package com.FGInteractive.BatalhaNaval.match.mode;

import com.FGInteractive.BatalhaNaval.match.model.Cell;
import java.util.ArrayList;
import java.util.List;

/**
 * Equilateral triangular tiles. Row r contains 2*r+1 alternating up/down
 * triangles; a board of side N therefore contains N*N individual triangles.
 * Each cell has at most three EDGE-adjacent neighbors, not the square-grid four.
 */
public record TriangularTileGeometry(int side) implements BoardGeometry {
    private static final double HEIGHT = Math.sqrt(3.0) / 2.0;

    public TriangularTileGeometry {
        if (side < 3 || side > 20)
            throw new IllegalArgumentException("Triangle side must be 3..20");
    }

    @Override public String kind() { return "TRIANGULAR_TILES"; }
    @Override public int rows() { return side; }
    @Override public int columns() { return side * 2 - 1; }

    @Override
    public boolean contains(Cell cell) {
        return cell != null && cell.row() >= 0 && cell.row() < side
            && cell.col() >= 0 && cell.col() <= 2 * cell.row();
    }

    @Override
    public List<Cell> neighbors(Cell cell) {
        if (!contains(cell)) return List.of();
        int row = cell.row(), col = cell.col();
        List<Cell> result = new ArrayList<>(3);
        if (col > 0) result.add(new Cell(row, col - 1));
        if (col < 2 * row) result.add(new Cell(row, col + 1));
        // Up triangles share their base with a downward tile on the next row;
        // down triangles share their top with an up tile on the previous row.
        if (col % 2 == 0 && row + 1 < side)
            result.add(new Cell(row + 1, col + 1));
        if (col % 2 != 0 && row > 0)
            result.add(new Cell(row - 1, col - 1));
        return List.copyOf(result);
    }

    @Override
    public List<TileShape> tiles() {
        List<TileShape> result = new ArrayList<>(side * side);
        for (Cell cell : cells()) {
            int row = cell.row(), col = cell.col();
            int k = col / 2;
            double topX = (side - row) / 2.0 + k;
            double bottomX = (side - row - 1) / 2.0 + k;
            double topY = row * HEIGHT, bottomY = (row + 1) * HEIGHT;
            if (col % 2 == 0) {
                result.add(new TileShape(cell, "TRIANGLE_UP", List.of(
                    new TileShape.Point(topX, topY),
                    new TileShape.Point(bottomX, bottomY),
                    new TileShape.Point(bottomX + 1, bottomY))));
            } else {
                result.add(new TileShape(cell, "TRIANGLE_DOWN", List.of(
                    new TileShape.Point(topX, topY),
                    new TileShape.Point(topX + 1, topY),
                    new TileShape.Point(bottomX + 1, bottomY))));
            }
        }
        return List.copyOf(result);
    }
}
