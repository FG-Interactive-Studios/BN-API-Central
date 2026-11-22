package com.FGInteractive.BatalhaNaval.match;

import static org.junit.jupiter.api.Assertions.*;
import com.FGInteractive.BatalhaNaval.match.dto.PlaceFleetRequest.ShipPlacement;
import com.FGInteractive.BatalhaNaval.match.mode.*;
import com.FGInteractive.BatalhaNaval.match.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SquareAbilitiesTests {
    private static GameModeDefinition pilot(int rows, int cols) {
        GameModeDefinition base = GameModePresets.classic();
        EnumMap<RuleSlot, RuleModule> rules = new EnumMap<>(base.rules());
        rules.put(RuleSlot.ABILITY, new SquareAbilityRule());
        return new GameModeDefinition("pilot", "Pilot", new RectangularGeometry(rows, cols),
            new FleetDefinition(List.of(new ShipDefinition("SCOUT", 2))),
            new LinearPlacementRule(), rules);
    }
    private static Board board(GameModeDefinition mode, int row, int col) {
        return Board.from(mode,
            List.of(new ShipPlacement("SCOUT", row, col, Orientation.HORIZONTAL)));
    }
    private static BattleRound round(GameModeDefinition mode) {
        return new BattleRound("ABC123", mode, 10, 11,
            board(mode, 0, 0), board(mode, 1, 1));
    }

    @Test void pilotIsOptInAndGeometryIsValidatedBeforeRegistration() {
        assertEquals("disabled", GameModePresets.classic().rules().get(RuleSlot.ABILITY).id());
        assertEquals("disabled", GameModePresets.quick().rules().get(RuleSlot.ABILITY).id());
        assertEquals("square-pilot-v1", GameModePresets.classicPowers().rules().get(RuleSlot.ABILITY).id());
        assertEquals(64, GameModePresets.classicPowers().geometry().cells().size());
        assertThrows(IllegalArgumentException.class,
            () -> new GameModeRegistry(List.of(GameModePresets.classic(), pilot(4, 5))));
        GameModeDefinition base = GameModePresets.triangular();
        EnumMap<RuleSlot, RuleModule> rules = new EnumMap<>(base.rules());
        rules.put(RuleSlot.ABILITY, new SquareAbilityRule());
        GameModeDefinition triangle = new GameModeDefinition("trianglepilot", "Triangle",
            base.geometry(), base.fleet(), base.placement(), rules);
        assertThrows(IllegalArgumentException.class,
            () -> new GameModeRegistry(List.of(GameModePresets.classic(), triangle)));
    }

    @Test void doubleShotResolvesBothHitsAndVictoryInOneTurn() {
        var game = round(pilot(4, 4));
        game.useAbility(10, "double-shot", List.of(new Cell(1, 1), new Cell(1, 2)));
        assertEquals("FINISHED", game.view(10).status());
        assertEquals(10L, game.view(10).winnerId().longValue());
        assertEquals(2, game.view(10).shotsFired().size());
        assertEquals("SCOUT", game.view(10).shotsFired().get(1).sunkShip());
        assertEquals(2, game.view(11).shotsReceived().size());
        assertEquals(0, game.view(10).yourAbilities().get(0).usesRemaining());
    }

    @Test void invalidTargetsDoNotSpendTurnOrAbilityAndDefaultModeBlocksIt() {
        var game = round(pilot(4, 4));
        assertThrows(IllegalArgumentException.class,
            () -> game.useAbility(10, "double-shot", List.of(new Cell(1, 1), new Cell(4, 1))));
        assertThrows(IllegalArgumentException.class,
            () -> game.useAbility(10, "double-shot", List.of(new Cell(1, 1), new Cell(1, 1))));
        assertThrows(IllegalArgumentException.class,
            () -> game.useAbility(10, "made-up", List.of(new Cell(1, 1))));
        assertThrows(IllegalStateException.class,
            () -> game.useAbility(11, "radar", List.of(new Cell(1, 1))));
        assertEquals(1, game.view(10).revision());
        assertTrue(game.view(10).shotsFired().isEmpty());
        assertEquals(1, game.view(10).yourAbilities().get(0).usesRemaining());

        game.fire(10, new Cell(1, 1));
        game.fire(11, new Cell(3, 3));
        assertThrows(IllegalStateException.class,
            () -> game.useAbility(10, "double-shot", List.of(new Cell(1, 1), new Cell(1, 2))));
        assertEquals(10L, game.view(10).turnPlayerId().longValue());
        assertEquals(1, game.view(10).yourAbilities().get(0).usesRemaining());

        var classic = GameModePresets.classic();
        var regular = new BattleRound("ABC123", classic, 10, 11,
            Board.random(classic), Board.random(classic));
        assertThrows(IllegalStateException.class,
            () -> regular.useAbility(10, "radar", List.of(new Cell(1, 1))));
        assertTrue(regular.view(10).yourAbilities().isEmpty());
    }

    @Test void radarOnlyReturnsAggregateInOwnerSnapshotAndSpendsOneTurn() {
        var game = round(pilot(4, 4));
        game.useAbility(10, "radar", List.of(new Cell(1, 1)));
        assertEquals(2, game.view(10).yourRadarReports().get(0).contacts());
        assertEquals(1, game.view(10).yourRadarReports().size());
        assertTrue(game.view(11).yourRadarReports().isEmpty());
        assertTrue(game.view(10).shotsFired().isEmpty());
        assertEquals(11L, game.view(10).turnPlayerId().longValue());
        game.fire(11, new Cell(3, 3));
        assertThrows(IllegalStateException.class,
            () -> game.useAbility(10, "radar", List.of(new Cell(1, 1))));
        assertEquals(0, game.view(10).yourAbilities().stream()
            .filter(a -> a.id().equals("radar")).findFirst().orElseThrow().usesRemaining());
    }

    @Test void twoMissesSpendOneTurnButNotTwoAndNoOpponentSecretsLeak() {
        var game = round(pilot(4, 4));
        game.useAbility(10, "double-shot", List.of(new Cell(3, 0), new Cell(3, 1)));
        assertEquals(2, game.view(10).shotsFired().size());
        assertEquals(11L, game.view(10).turnPlayerId().longValue());
        assertEquals(2, game.view(10).revision());
        assertTrue(game.view(11).yourRadarReports().isEmpty());
        assertEquals(2, game.view(11).shotsReceived().size());
        game.fire(11, new Cell(3, 3));
        assertThrows(IllegalStateException.class,
            () -> game.useAbility(10, "double-shot", List.of(new Cell(2, 0), new Cell(2, 1))));
    }
}
