package com.FGInteractive.BatalhaNaval.match.mode;
import java.util.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GameModePresets {
    private static final PlacementRule LINEAR = new LinearPlacementRule();
    private static final PlacementRule TRIANGLE = new TriangularEdgePlacementRule();
    private static final PlacementRule HEXAGON = new HexagonalPlacementRule();

    private static Map<RuleSlot, RuleModule> classicMechanics() {
        return Map.of(
            RuleSlot.MOVEMENT, new DeclaredRule("stationary", RuleSlot.MOVEMENT),
            RuleSlot.ATTACK, new StandardShotRule(),
            RuleSlot.TURN, new AlternatingTurnRule(),
            RuleSlot.VICTORY, new AllShipsSunkRule(),
            RuleSlot.ABILITY, new DeclaredRule("disabled", RuleSlot.ABILITY));
    }
    /** Official base fleet from Game Design GDD v1.2: one ship for each
     * currently delivered class. Class multiplicity is awaiting GD definition;
     * additional legacy classes remain confined to disabled experimental modes.
     */
    private static FleetDefinition baseFleet() {
        return new FleetDefinition(List.of(
            new ShipDefinition("SEARCH",1),
            new ShipDefinition("DESTROYER",2),
            new ShipDefinition("CARRIER",5)));
    }
    public static GameModeDefinition classic() {
        return new GameModeDefinition("classic", "Classico",new RectangularGeometry(8,8),
            baseFleet(),LINEAR,classicMechanics());
    }
    public static GameModeDefinition quick() {
        return new GameModeDefinition("quick", "Rapido", new RectangularGeometry(8,8),
            new FleetDefinition(List.of(
                new ShipDefinition("BATTLESHIP",4),new ShipDefinition("CRUISER",3),
                new ShipDefinition("DESTROYER",2))),LINEAR,classicMechanics());
    }
    /** A triangular mask (square cells), proving that placement is not tied to rectangles. */
    public static GameModeDefinition triangular() {
        return new GameModeDefinition("triangular", "Triangular",
            new TriangularGeometry(10),
            new FleetDefinition(List.of(
                new ShipDefinition("BATTLESHIP",4), new ShipDefinition("CRUISER",3),
                new ShipDefinition("DESTROYER",2))), LINEAR, classicMechanics());
    }
    /**
     * Real equilateral triangle tiles, each a distinct hittable cell.
     * The older "triangular" mode is deliberately preserved as a square mask.
     */
    public static GameModeDefinition triangleTiles() {
        return new GameModeDefinition("triangle-tiles", "Triangulo de celulas",
            new TriangularTileGeometry(8),
            new FleetDefinition(List.of(
                new ShipDefinition("BATTLESHIP",4), new ShipDefinition("CRUISER",3),
                new ShipDefinition("DESTROYER",2))), TRIANGLE, classicMechanics());
    }
    /** 61 playable regular hexagons (radius 4), straight vertical and diagonal ships. */
    public static GameModeDefinition hexagons() {
        return new GameModeDefinition("hexagons", "Hexagonal",
            new HexagonalGeometry(4),
            new FleetDefinition(List.of(new ShipDefinition("BATTLESHIP",4),
                new ShipDefinition("CRUISER",3),new ShipDefinition("DESTROYER",2))),
            HEXAGON, classicMechanics());
    }

    /** Square powers pilot shares the official 8x8 board and three base classes. */
    public static GameModeDefinition classicPowers() {
        GameModeDefinition base = classic();
        EnumMap<RuleSlot, RuleModule> rules = new EnumMap<>(base.rules());
        rules.put(RuleSlot.ABILITY, new SquareAbilityRule());
        return new GameModeDefinition("classic-powers", "Classico com poderes (piloto)",
            base.geometry(), base.fleet(), base.placement(), rules);
    }

    @Bean public GameModeDefinition classicPowersGameMode() { return classicPowers(); }
    @Bean public GameModeDefinition hexagonsGameMode() { return hexagons(); }
    @Bean public GameModeDefinition triangleTilesGameMode() { return triangleTiles(); }
    @Bean public GameModeDefinition triangularGameMode() { return triangular(); }
    @Bean public GameModeDefinition classicGameMode() { return classic(); }
    @Bean public GameModeDefinition quickGameMode() { return quick(); }
}
