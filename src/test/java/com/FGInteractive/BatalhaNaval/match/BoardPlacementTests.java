package com.FGInteractive.BatalhaNaval.match;

import static org.junit.jupiter.api.Assertions.*;
import com.FGInteractive.BatalhaNaval.match.dto.PlaceFleetRequest.ShipPlacement;
import com.FGInteractive.BatalhaNaval.match.model.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoardPlacementTests {
    private static List<ShipPlacement> valid() {
        return List.of(
            new ShipPlacement(ShipType.CARRIER, 0, 0, Orientation.HORIZONTAL),
            new ShipPlacement(ShipType.BATTLESHIP, 1, 0, Orientation.HORIZONTAL),
            new ShipPlacement(ShipType.CRUISER, 2, 0, Orientation.HORIZONTAL),
            new ShipPlacement(ShipType.SUBMARINE, 3, 0, Orientation.HORIZONTAL),
            new ShipPlacement(ShipType.DESTROYER, 4, 0, Orientation.HORIZONTAL));
    }

    @Test void acceptsExactlyFiveShipsAndSeventeenUniqueCells() {
        Board board = Board.from(valid());
        assertEquals(5, board.ships().size());
        assertEquals(17, board.occupied().size());
        assertTrue(board.occupied().contains(new Board.Cell(0,4)));
        assertFalse(board.occupied().contains(new Board.Cell(9,9)));
        assertThrows(UnsupportedOperationException.class, () -> board.ships().clear());
        assertThrows(UnsupportedOperationException.class,
            () -> board.occupied().add(new Board.Cell(9,9)));
    }

    @Test void rejectsIncompleteDuplicateOrNullFleetInputs() {
        assertThrows(IllegalArgumentException.class, () -> Board.from(null));
        assertThrows(IllegalArgumentException.class, () -> Board.from(valid().subList(0,4)));
        List<ShipPlacement> duplicated = new ArrayList<>(valid());
        duplicated.set(4, new ShipPlacement(ShipType.CARRIER, 5,0,Orientation.HORIZONTAL));
        assertThrows(IllegalArgumentException.class, () -> Board.from(duplicated));
        for (ShipPlacement invalid : List.of(
            new ShipPlacement(null,0,0,Orientation.HORIZONTAL),
            new ShipPlacement(ShipType.CARRIER,0,0,null),
            new ShipPlacement(ShipType.CARRIER,null,0,Orientation.HORIZONTAL),
            new ShipPlacement(ShipType.CARRIER,0,null,Orientation.HORIZONTAL))) {
            List<ShipPlacement> replacements = new ArrayList<>(valid());
            replacements.set(0, invalid);
            assertThrows(IllegalArgumentException.class, () -> Board.from(replacements));
        }
    }

    @Test void rejectsOverlapAndOutOfBoundsInBothOrientations() {
        List<ShipPlacement> overlapped = new ArrayList<>(valid());
        overlapped.set(1, new ShipPlacement(ShipType.BATTLESHIP,0,3,Orientation.VERTICAL));
        assertThrows(IllegalArgumentException.class, () -> Board.from(overlapped));

        for (ShipPlacement invalid : List.of(
            new ShipPlacement(ShipType.CARRIER,0,6,Orientation.HORIZONTAL),
            new ShipPlacement(ShipType.CARRIER,6,0,Orientation.VERTICAL),
            new ShipPlacement(ShipType.CARRIER,-1,0,Orientation.HORIZONTAL),
            new ShipPlacement(ShipType.CARRIER,10,0,Orientation.VERTICAL),
            new ShipPlacement(ShipType.CARRIER,0,-1,Orientation.HORIZONTAL))) {
            List<ShipPlacement> replacements = new ArrayList<>(valid());
            replacements.set(0, invalid);
            assertThrows(IllegalArgumentException.class, () -> Board.from(replacements));
        }
        // Adjacency is allowed; only overlap is forbidden.
        assertDoesNotThrow(() -> Board.from(valid()));
    }

    @Test void generatesManyLegalRandomFleets() {
        for (int i = 0; i < 150; i++) {
            Board generated = Board.random();
            assertEquals(5, generated.ships().size());
            assertEquals(17, generated.occupied().size());
            assertEquals(17, Board.from(generated.ships()).occupied().size());
        }
    }

    @Test void eachPlayerSeesOnlyOwnShipsEvenAfterBothConfirm() {
        PreparationRound round = new PreparationRound("ABC234", 10, 11);
        Board a = Board.from(valid());
        Board b = Board.random();
        round.place(10, a);
        assertEquals(5, round.view(10).yourShips().size());
        assertTrue(round.view(11).yourShips().isEmpty());
        assertTrue(round.view(11).opponentPlaced());
        assertFalse(round.view(11).opponentConfirmed());
        round.place(11, b);
        round.confirm(10);
        assertThrows(IllegalStateException.class, () -> round.place(10, Board.random()));
        assertFalse(round.view(10).bothConfirmed());
        round.confirm(11);
        long revision = round.view(11).revision();
        round.confirm(11); // repeat confirmation must be idempotent
        assertEquals(revision, round.view(11).revision());
        assertTrue(round.view(10).bothConfirmed());
        assertEquals(a.ships(), round.view(10).yourShips());
        assertEquals(b.ships(), round.view(11).yourShips());
        assertNotEquals(round.view(10).yourShips(), round.view(11).yourShips());
        assertThrows(IllegalArgumentException.class, () -> round.view(12));
    }
}
