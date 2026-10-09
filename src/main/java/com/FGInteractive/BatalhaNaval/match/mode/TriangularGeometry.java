package com.FGInteractive.BatalhaNaval.match.mode;
import com.FGInteractive.BatalhaNaval.match.model.Cell;

/**
 * Triangular playable mask of square cells, not triangular tiles.
 * Row 0 has one cell, row N-1 has N cells. Future true triangular tiles can
 * implement the same interface with a different coordinate/topology policy.
 */
public record TriangularGeometry(int size) implements BoardGeometry {
    public TriangularGeometry {
        if (size < 3 || size > 30) throw new IllegalArgumentException("Invalid triangle size");
    }
    public String kind() { return "TRIANGULAR_MASK"; }
    public int rows() { return size; }
    public int columns() { return size; }
    public boolean contains(Cell c) {
        return c != null && c.row() >= 0 && c.row() < size
            && c.col() >= 0 && c.col() <= c.row();
    }
}
