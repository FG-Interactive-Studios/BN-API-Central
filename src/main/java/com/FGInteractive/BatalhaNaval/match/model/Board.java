package com.FGInteractive.BatalhaNaval.match.model;

import com.FGInteractive.BatalhaNaval.match.dto.PlaceFleetRequest.ShipPlacement;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable, authoritative private placement. No JPA or game-session persistence. */
public final class Board {
    public static final int SIZE = 10;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final List<ShipPlacement> ships;
    private final Set<Cell> occupied;

    private Board(List<ShipPlacement> ships, Set<Cell> occupied) {
        this.ships = List.copyOf(ships);
        this.occupied = Set.copyOf(occupied);
    }

    public List<ShipPlacement> ships() { return ships; }
    public Set<Cell> occupied() { return occupied; }

    public static Board from(List<ShipPlacement> placements) {
        if (placements == null || placements.size() != ShipType.values().length) {
            throw new IllegalArgumentException("Exactly five ships are required");
        }
        EnumSet<ShipType> types = EnumSet.noneOf(ShipType.class);
        Set<Cell> cells = new HashSet<>();
        for (ShipPlacement ship : placements) {
            if (ship == null || ship.type() == null || ship.orientation() == null) {
                throw new IllegalArgumentException("Ship type and orientation are required");
            }
            if (!types.add(ship.type())) {
                throw new IllegalArgumentException("Each ship type must appear exactly once");
            }
            int size = ship.type().length();
            if (ship.row() < 0 || ship.col() < 0
                || ship.row() >= SIZE || ship.col() >= SIZE
                || (ship.orientation() == Orientation.HORIZONTAL && ship.col() > SIZE - size)
                || (ship.orientation() == Orientation.VERTICAL && ship.row() > SIZE - size)) {
                throw new IllegalArgumentException("Ship is outside the 10x10 board");
            }
            for (int i = 0; i < size; i++) {
                Cell cell = new Cell(
                    ship.row() + (ship.orientation() == Orientation.VERTICAL ? i : 0),
                    ship.col() + (ship.orientation() == Orientation.HORIZONTAL ? i : 0));
                if (!cells.add(cell)) {
                    throw new IllegalArgumentException("Ships cannot overlap");
                }
            }
        }
        // Stable ordering, independent from input order.
        List<ShipPlacement> ordered = new ArrayList<>(placements);
        ordered.sort(java.util.Comparator.comparing(ShipPlacement::type));
        return new Board(ordered, cells);
    }

    public static Board random() {
        List<ShipPlacement> result = new ArrayList<>(ShipType.values().length);
        Set<Cell> cells = new HashSet<>();
        for (ShipType type : ShipType.values()) {
            boolean placed = false;
            for (int attempt = 0; attempt < 1000; attempt++) {
                Orientation orientation = RANDOM.nextBoolean()
                    ? Orientation.HORIZONTAL : Orientation.VERTICAL;
                int row = RANDOM.nextInt(SIZE);
                int col = RANDOM.nextInt(SIZE);
                int lastRow = row + (orientation == Orientation.VERTICAL ? type.length() - 1 : 0);
                int lastCol = col + (orientation == Orientation.HORIZONTAL ? type.length() - 1 : 0);
                if (lastRow >= SIZE || lastCol >= SIZE) continue;
                List<Cell> candidate = new ArrayList<>(type.length());
                for (int i = 0; i < type.length(); i++) {
                    candidate.add(new Cell(row + (orientation == Orientation.VERTICAL ? i : 0),
                        col + (orientation == Orientation.HORIZONTAL ? i : 0)));
                }
                if (candidate.stream().anyMatch(cells::contains)) continue;
                cells.addAll(candidate);
                result.add(new ShipPlacement(type, row, col, orientation));
                placed = true;
                break;
            }
            if (!placed) throw new IllegalStateException("Could not generate a valid random fleet");
        }
        return from(result);
    }

    public record Cell(int row, int col) {}
}
