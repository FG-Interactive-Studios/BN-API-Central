package com.FGInteractive.BatalhaNaval.match;

import static org.junit.jupiter.api.Assertions.*;
import com.FGInteractive.BatalhaNaval.match.dto.PlaceFleetRequest.ShipPlacement;
import com.FGInteractive.BatalhaNaval.match.mode.*;
import com.FGInteractive.BatalhaNaval.match.model.Board;
import com.FGInteractive.BatalhaNaval.match.model.Orientation;
import java.util.*;
import org.junit.jupiter.api.Test;

class GameModeCompositionTests {
    @Test void modesComposeDifferentFleetSizesAndGeometryWithoutChangingBoardLogic() {
        GameModeDefinition classic = GameModePresets.classic();
        GameModeDefinition quick = GameModePresets.quick();
        GameModeDefinition triangle = GameModePresets.triangular();
        assertEquals(100, classic.geometry().cells().size());
        assertEquals(64, quick.geometry().cells().size());
        assertEquals(55, triangle.geometry().cells().size());
        assertEquals(17, Board.random(classic).occupied().size());
        assertEquals(9, Board.random(quick).occupied().size());
        assertEquals(9, Board.random(triangle).occupied().size());
        assertEquals("linear", triangle.placement().id());
        assertEquals("disabled", triangle.rules().get(RuleSlot.ABILITY).id());
        assertEquals(3, quick.summary().fleet().size());

        for (int i = 0; i < 60; i++) {
            for (GameModeDefinition mode : List.of(classic, quick, triangle)) {
                Board board = Board.random(mode);
                assertEquals(mode.fleet().occupiedCells(), board.occupied().size());
                assertEquals(board.occupied(), Board.from(mode, board.ships()).occupied());
                assertTrue(board.occupied().stream().allMatch(mode.geometry()::contains));
            }
        }
    }

    @Test void triangularGeometryHasCorrectPlayableCellsAndNeighbors() {
        var triangle = new TriangularGeometry(8);
        assertTrue(triangle.contains(new Board.Cell(7, 7)));
        assertTrue(triangle.contains(new Board.Cell(2, 1)));
        assertFalse(triangle.contains(new Board.Cell(1, 3)));
        assertFalse(triangle.contains(new Board.Cell(-1, 0)));
        assertEquals(36, triangle.cells().size());
        assertEquals(1, triangle.neighbors(new Board.Cell(0, 0)).size());
        assertTrue(triangle.neighbors(new Board.Cell(2, 2)).contains(new Board.Cell(3, 2)));
        assertFalse(triangle.neighbors(new Board.Cell(2, 2)).contains(new Board.Cell(1, 2)));
    }

    @Test void differentFleetsCannotBeInterchangedOrSpoofed() {
        var quick = GameModePresets.quick();
        var valid = List.of(
            new ShipPlacement("BATTLESHIP", 0, 0, Orientation.HORIZONTAL),
            new ShipPlacement("CRUISER", 1, 0, Orientation.HORIZONTAL),
            new ShipPlacement("DESTROYER", 2, 0, Orientation.HORIZONTAL));
        assertEquals(9, Board.from(quick, valid).occupied().size());
        assertThrows(IllegalArgumentException.class, () -> Board.from(GameModePresets.classic(), valid));
        var forged = new ArrayList<>(valid);
        forged.set(2, new ShipPlacement("CARRIER", 2, 0, Orientation.HORIZONTAL));
        assertThrows(IllegalArgumentException.class, () -> Board.from(quick, forged));
        var badEdge = new ArrayList<>(valid);
        badEdge.set(0, new ShipPlacement("BATTLESHIP", 0, 5, Orientation.HORIZONTAL));
        assertThrows(IllegalArgumentException.class, () -> Board.from(quick, badEdge));

        var triangle = GameModePresets.triangular();
        assertThrows(IllegalArgumentException.class, () -> Board.from(triangle, valid));
        var playable = List.of(
            new ShipPlacement("BATTLESHIP", 9, 0, Orientation.HORIZONTAL),
            new ShipPlacement("CRUISER", 8, 0, Orientation.HORIZONTAL),
            new ShipPlacement("DESTROYER", 7, 0, Orientation.HORIZONTAL));
        assertEquals(9, Board.from(triangle, playable).occupied().size());
    }

    @Test void registryRejectsUnknownModeAndMalformedModuleCompositions() {
        var registry = new GameModeRegistry(List.of(
            GameModePresets.classic(), GameModePresets.quick(), GameModePresets.triangular()));
        assertEquals("classic", registry.get(null).id());
        assertEquals("quick", registry.get("quick").id());
        assertEquals(3, registry.list().size());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> registry.get("nonexistent"));
        assertThrows(IllegalStateException.class, () ->
            new GameModeRegistry(List.of(GameModePresets.classic(), GameModePresets.classic())));
        assertThrows(IllegalArgumentException.class, () ->
            new GameModeDefinition("broken", "Broken", new RectangularGeometry(8,8),
                new FleetDefinition(List.of(new ShipDefinition("SHIP",2))),
                new LinearPlacementRule(), Map.of(RuleSlot.MOVEMENT,
                    new DeclaredRule("static", RuleSlot.MOVEMENT))));
        assertThrows(IllegalArgumentException.class, () ->
            new FleetDefinition(List.of(new ShipDefinition("SHIP",2),
                new ShipDefinition("SHIP",3))));
    }

    @Test void anIndependentDeveloperModuleCanBeComposedAndValidated() {
        var movement = new RuleModule() {
            @Override public String id() { return "custom-static"; }
            @Override public RuleSlot slot() { return RuleSlot.MOVEMENT; }
            @Override public void validate(GameModeDefinition mode) {
                if (!mode.geometry().kind().equals("RECTANGULAR"))
                    throw new IllegalArgumentException("Custom movement needs rectangular geometry");
            }
        };
        var rules = new EnumMap<RuleSlot, RuleModule>(RuleSlot.class);
        rules.putAll(GameModePresets.classic().rules());
        rules.put(RuleSlot.MOVEMENT, movement);
        var custom = new GameModeDefinition("custom", "Custom", new RectangularGeometry(8,8),
            new FleetDefinition(List.of(new ShipDefinition("SCOUT",2))),
            new LinearPlacementRule(), rules);
        var registered = new GameModeRegistry(List.of(GameModePresets.classic(), custom));
        assertEquals("custom-static", registered.get("custom").rules().get(RuleSlot.MOVEMENT).id());
        assertEquals(2, Board.random(custom).occupied().size());

        var incompatible = new GameModeDefinition("incompatible", "Incompatible",
            new TriangularGeometry(8), custom.fleet(), custom.placement(), custom.rules());
        assertThrows(IllegalArgumentException.class, () ->
            new GameModeRegistry(List.of(GameModePresets.classic(), incompatible)));
    }
}
