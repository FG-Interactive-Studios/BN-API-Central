package com.FGInteractive.BatalhaNaval.match.mode;
import java.util.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GameModePresets {
    private static final PlacementRule LINEAR = new LinearPlacementRule();

    private static Map<RuleSlot, RuleModule> classicMechanics() {
        return Map.of(
            RuleSlot.MOVEMENT, new DeclaredRule("stationary", RuleSlot.MOVEMENT),
            RuleSlot.ATTACK, new DeclaredRule("standard-shot", RuleSlot.ATTACK),
            RuleSlot.TURN, new DeclaredRule("alternating", RuleSlot.TURN),
            RuleSlot.VICTORY, new DeclaredRule("all-ships-sunk", RuleSlot.VICTORY),
            RuleSlot.ABILITY, new DeclaredRule("disabled", RuleSlot.ABILITY));
    }
    public static GameModeDefinition classic() {
        return new GameModeDefinition("classic", "Classico",new RectangularGeometry(10,10),
            new FleetDefinition(List.of(
                new ShipDefinition("CARRIER",5),new ShipDefinition("BATTLESHIP",4),
                new ShipDefinition("CRUISER",3),new ShipDefinition("SUBMARINE",3),
                new ShipDefinition("DESTROYER",2))),LINEAR,classicMechanics());
    }
    public static GameModeDefinition quick() {
        return new GameModeDefinition("quick", "Rapido", new RectangularGeometry(8,8),
            new FleetDefinition(List.of(
                new ShipDefinition("BATTLESHIP",4),new ShipDefinition("CRUISER",3),
                new ShipDefinition("DESTROYER",2))),LINEAR,classicMechanics());
    }
    @Bean public GameModeDefinition classicGameMode() { return classic(); }
    @Bean public GameModeDefinition quickGameMode() { return quick(); }
}
