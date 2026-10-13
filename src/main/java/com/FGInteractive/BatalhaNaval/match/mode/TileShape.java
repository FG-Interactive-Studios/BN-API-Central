package com.FGInteractive.BatalhaNaval.match.mode;

import com.FGInteractive.BatalhaNaval.match.model.Cell;
import java.util.List;

/** Public rendering metadata. Coordinates are board-local, never a ship position. */
public record TileShape(Cell cell, String shape, List<Point> vertices) {
    public TileShape {
        if (cell == null || shape == null || vertices == null || vertices.size() < 3)
            throw new IllegalArgumentException("Invalid tile polygon");
        vertices = List.copyOf(vertices);
    }
    public record Point(double x, double y) {
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(y))
                throw new IllegalArgumentException("Invalid tile vertex");
        }
    }
}
