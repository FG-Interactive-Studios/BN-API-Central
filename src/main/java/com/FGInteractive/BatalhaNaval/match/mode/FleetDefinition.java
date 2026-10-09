package com.FGInteractive.BatalhaNaval.match.mode;
import java.util.*;
public record FleetDefinition(List<ShipDefinition> ships) {
    public FleetDefinition {
        if (ships == null || ships.isEmpty() || ships.size() > 20)
            throw new IllegalArgumentException("Fleet must contain 1..20 ships");
        ships = List.copyOf(ships);
        Set<String> ids = new HashSet<>();
        for (ShipDefinition ship : ships) {
            if (!ids.add(ship.id())) throw new IllegalArgumentException("Duplicate ship type: " + ship.id());
        }
    }
    public ShipDefinition require(String id) {
        return ships.stream().filter(ship -> ship.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Ship type is not in this game mode"));
    }
    public int occupiedCells() {
        return ships.stream().mapToInt(ShipDefinition::length).sum();
    }
}
