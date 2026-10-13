package com.FGInteractive.BatalhaNaval.match.mode;
import com.FGInteractive.BatalhaNaval.match.model.Cell;
import java.util.ArrayList;
import java.util.List;

/** Topology of playable cells, independent from fleet size or combat behavior. */
public interface BoardGeometry {
    String kind();
    int rows();
    int columns();
    boolean contains(Cell cell);

    default List<Cell> cells() {
        List<Cell> result = new ArrayList<>();
        for (int row=0; row<rows(); row++) for (int col=0; col<columns(); col++) {
            Cell candidate = new Cell(row,col);
            if (contains(candidate)) result.add(candidate);
        }
        return List.copyOf(result);
    }

    /**
     * Public client-side polygons. The client renders these vertices and sends
     * the cell's (row,col) when clicked; it never guesses the board's topology.
     * Rectangular and legacy triangular-mask modes default to unit squares.
     */
    default List<TileShape> tiles() {
        List<TileShape> result = new ArrayList<>();
        for (Cell cell : cells()) {
            double x = cell.col(), y = cell.row();
            result.add(new TileShape(cell, "SQUARE", List.of(
                new TileShape.Point(x, y), new TileShape.Point(x + 1, y),
                new TileShape.Point(x + 1, y + 1), new TileShape.Point(x, y + 1))));
        }
        return List.copyOf(result);
    }

    default List<Cell> neighbors(Cell cell) {
        if (!contains(cell)) return List.of();
        List<Cell> list = new ArrayList<>(4);
        for (Cell other : List.of(new Cell(cell.row()-1,cell.col()),
            new Cell(cell.row()+1,cell.col()), new Cell(cell.row(),cell.col()-1),
            new Cell(cell.row(),cell.col()+1))) {
            if (contains(other)) list.add(other);
        }
        return List.copyOf(list);
    }
}
