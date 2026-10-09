package com.FGInteractive.BatalhaNaval.match.model;

import com.FGInteractive.BatalhaNaval.match.dto.PlaceFleetRequest.ShipPlacement;
import com.FGInteractive.BatalhaNaval.match.mode.*;
import java.security.SecureRandom;
import java.util.*;

/** Immutable private placement validated against a fixed mode definition. */
public final class Board {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final List<ShipPlacement> ships;
    private final Set<Cell> occupied;

    private Board(List<ShipPlacement> ships, Set<Cell> occupied) {
        this.ships = List.copyOf(ships);
        this.occupied = Set.copyOf(occupied);
    }
    public List<ShipPlacement> ships() { return ships; }
    public Set<Cell> occupied() { return occupied; }

    /** Convenience for classic-mode legacy callers and simple unit tests. */
    public static Board from(List<ShipPlacement> placements) {
        return from(GameModePresets.classic(), placements);
    }

    public static Board from(GameModeDefinition mode, List<ShipPlacement> placements) {
        Objects.requireNonNull(mode, "mode");
        if (placements == null || placements.size() != mode.fleet().ships().size()) {
            throw new IllegalArgumentException("Wrong number of ships for mode " + mode.id());
        }
        Set<String> types = new HashSet<>();
        Set<Cell> cells = new HashSet<>();
        for (ShipPlacement ship : placements) {
            if (ship == null || ship.type() == null) {
                throw new IllegalArgumentException("Ship type is required");
            }
            if (!types.add(ship.type())) {
                throw new IllegalArgumentException("Each ship type must appear exactly once");
            }
            ShipDefinition definition = mode.fleet().require(ship.type());
            List<Cell> footprint = mode.placement().footprint(ship, definition, mode.geometry());
            mode.placement().validateAgainstExisting(footprint, cells);
            cells.addAll(footprint);
        }
        List<ShipPlacement> ordered = new ArrayList<>(placements);
        ordered.sort(Comparator.comparing(ShipPlacement::type));
        return new Board(ordered, cells);
    }

    public static Board random() {
        return random(GameModePresets.classic());
    }

    public static Board random(GameModeDefinition mode) {
        Objects.requireNonNull(mode, "mode");
        List<Cell> available = mode.geometry().cells();
        List<ShipDefinition> fleet = new ArrayList<>(mode.fleet().ships());
        fleet.sort(Comparator.comparingInt(ShipDefinition::length).reversed());
        for (int restart = 0; restart < 30; restart++) {
            List<ShipPlacement> placements = new ArrayList<>();
            Set<Cell> occupied = new HashSet<>();
            boolean complete = true;
            for (ShipDefinition ship : fleet) {
                List<Cell> candidateOrigins = new ArrayList<>(available);
                Collections.shuffle(candidateOrigins, RANDOM);
                boolean found = false;
                for (Cell start : candidateOrigins) {
                    List<Orientation> orientations = new ArrayList<>(List.of(Orientation.values()));
                    Collections.shuffle(orientations, RANDOM);
                    for (Orientation orientation : orientations) {
                        ShipPlacement placement = new ShipPlacement(
                            ship.id(), start.row(), start.col(), orientation);
                        List<Cell> cells;
                        try { cells = mode.placement().footprint(placement, ship, mode.geometry()); }
                        catch (IllegalArgumentException ex) { continue; }
                        if (cells.stream().anyMatch(occupied::contains)) continue;
                        placements.add(placement);
                        occupied.addAll(cells);
                        found = true;
                        break;
                    }
                    if (found) break;
                }
                if (!found) {
                    complete = false;
                    break;
                }
            }
            if (complete) return from(mode, placements);
        }
        throw new IllegalStateException("Mode has no feasible random fleet placement");
    }

    public record Cell(int row, int col) {}
}
