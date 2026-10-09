package com.FGInteractive.BatalhaNaval.match.mode;
import com.FGInteractive.BatalhaNaval.match.model.Cell;
public record RectangularGeometry(int rows, int columns) implements BoardGeometry {
    public RectangularGeometry {
        if (rows < 2 || columns < 2 || rows > 30 || columns > 30) {
            throw new IllegalArgumentException("Invalid board dimensions");
        }
    }
    public String kind() { return "RECTANGULAR"; }
    public boolean contains(Cell c) {
        return c != null && c.row() >= 0 && c.row() < rows
            && c.col() >= 0 && c.col() < columns;
    }
}
