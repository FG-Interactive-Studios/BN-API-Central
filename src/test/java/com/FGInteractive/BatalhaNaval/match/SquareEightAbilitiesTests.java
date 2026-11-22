package com.FGInteractive.BatalhaNaval.match;

import static org.junit.jupiter.api.Assertions.*;
import com.FGInteractive.BatalhaNaval.match.dto.PlaceFleetRequest.ShipPlacement;
import com.FGInteractive.BatalhaNaval.match.mode.*;
import com.FGInteractive.BatalhaNaval.match.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Contract tests for every registered ability and their interaction with ordinary shots. */
class SquareEightAbilitiesTests {
    private static GameModeDefinition pilot(int size) {
        GameModeDefinition base=GameModePresets.classic();
        Map<RuleSlot,RuleModule> rules=new EnumMap<>(base.rules());
        rules.put(RuleSlot.ABILITY,new SquareAbilityRule());
        return new GameModeDefinition("piloteight","Eight powers",new RectangularGeometry(size,size),
            new FleetDefinition(List.of(new ShipDefinition("SCOUT",2))),
            new LinearPlacementRule(),rules);
    }
    private static BattleRound battle(int size) {
        GameModeDefinition mode=pilot(size);
        return new BattleRound("XYZ123",mode,10,11,
            Board.from(mode,List.of(new ShipPlacement("SCOUT",0,0,Orientation.HORIZONTAL))),
            Board.from(mode,List.of(new ShipPlacement("SCOUT",1,1,Orientation.HORIZONTAL))));
    }
    private static Cell c(int row,int col){return new Cell(row,col);}

    @Test void allEightAvailableWithDynamicTargetMetadataInBothSquareDimensions() {
        for(int size:List.of(8,10)){
            var b=battle(size);
            assertEquals(8,b.view(10).yourAbilities().size());
            assertEquals(8,b.view(11).yourAbilities().size());
            assertEquals(List.of("double-shot","air-strike","line-barrage","hull-shield",
                "last-chance","radar","interference","retreat"),
                b.view(10).yourAbilities().stream().map(a->a.id()).toList());
            assertEquals("OWN",b.view(10).yourAbilities().get(7).targetScope());
            assertEquals(0,b.view(10).yourAbilities().get(4).targetCount());
            assertTrue(b.view(10).yourAbilities().stream().allMatch(a->a.usesRemaining()==1));
        }
    }

    @Test void airStrikeCanSinkFleetAndCannotClipBoardOrReattackUsedCells() {
        var b=battle(8);
        assertThrows(IllegalArgumentException.class,
            ()->b.useAbility(10,"air-strike",List.of(c(7,7))));
        assertEquals(1,b.view(10).revision());
        b.useAbility(10,"air-strike",List.of(c(1,1)));
        assertEquals("FINISHED",b.view(10).status());
        assertEquals(2,b.view(10).shotsFired().size()); // Stop as soon as victory is achieved.
        assertTrue(b.view(10).shotsFired().stream().allMatch(ShotRecord::hit));
    }

    @Test void lineBarrageRequiresThreeStraightCells() {
        var b=battle(8);
        assertThrows(IllegalArgumentException.class,
            ()->b.useAbility(10,"line-barrage",List.of(c(1,1),c(2,2))));
        assertThrows(IllegalArgumentException.class,
            ()->b.useAbility(10,"line-barrage",List.of(c(1,1),c(1,2))));
        b.useAbility(10,"line-barrage",List.of(c(1,0),c(1,2)));
        assertEquals("FINISHED",b.view(10).status());
        assertEquals(3,b.view(10).shotsFired().size());
        assertEquals("SCOUT",b.view(10).shotsFired().get(2).sunkShip());
    }

    @Test void shieldBlocksOneIncomingHitAndBlockedCellIsLegallyRetargetable() {
        var b=battle(8);
        assertThrows(IllegalArgumentException.class,
            ()->b.useAbility(10,"hull-shield",List.of(c(4,4))));
        b.useAbility(10,"hull-shield",List.of(c(0,0)));
        b.fire(11,c(0,0));
        assertTrue(b.view(11).shotsFired().get(0).blocked());
        assertFalse(b.view(11).shotsFired().get(0).hit());
        assertEquals(10L,b.view(10).turnPlayerId());
        b.fire(10,c(7,7));
        b.fire(11,c(0,0));
        assertTrue(b.view(11).shotsFired().get(0).hit());
        assertFalse(b.view(11).shotsFired().get(0).blocked());
        assertNull(b.view(11).shotsFired().get(0).sunkShip());
    }

    @Test void lastChanceProtectsOnlyAgainstLethalShotAndIsConsumed() {
        var b=battle(8);
        b.useAbility(10,"last-chance",List.of());
        b.fire(11,c(0,0));
        assertTrue(b.view(11).shotsFired().get(0).hit());
        b.fire(10,c(7,7));
        b.fire(11,c(0,1));
        assertTrue(b.view(11).shotsFired().get(1).blocked());
        assertEquals("PLAYING",b.view(10).status());
        b.fire(10,c(7,6));
        b.fire(11,c(0,1));
        assertEquals("FINISHED",b.view(10).status());
        assertEquals(11L,b.view(10).winnerId());
    }

    @Test void interferencePreventsOpponentPowerNotTheirNormalAttack() {
        var b=battle(8);
        b.useAbility(10,"interference",List.of());
        assertTrue(b.view(11).yourAbilities().stream().allMatch(a->a.locked()));
        assertFalse(b.view(10).yourAbilities().get(0).locked());
        assertThrows(IllegalStateException.class,
            ()->b.useAbility(11,"radar",List.of(c(1,1))));
        assertEquals(1,b.view(11).yourAbilities().get(5).usesRemaining());
        b.fire(11,c(7,7));
        b.fire(10,c(7,6));
        assertTrue(b.view(11).yourAbilities().stream().allMatch(a->a.locked()));
        b.fire(11,c(7,5));
        b.fire(10,c(7,4));
        assertTrue(b.view(11).yourAbilities().stream().noneMatch(a->a.locked()));
        b.useAbility(11,"radar",List.of(c(0,0)));
        assertEquals(2,b.view(11).yourRadarReports().get(0).contacts());
        assertTrue(b.view(10).yourRadarReports().isEmpty());
    }

    @Test void radarOnlyCountsEnemyCellsNotPreviouslyHit() {
        var b=battle(8);
        b.fire(10,c(1,1)); // first enemy ship cell is hit
        b.fire(11,c(7,7));
        b.useAbility(10,"radar",List.of(c(1,1)));
        assertEquals(1,b.view(10).yourRadarReports().get(0).contacts());
        assertTrue(b.view(11).yourRadarReports().isEmpty());
    }

    @Test void retreatMovesOnlyOwnUndamagedShipAndNeverLeaksNewLocation() {
        var b=battle(8);
        assertThrows(IllegalArgumentException.class,
            ()->b.useAbility(10,"retreat",List.of(c(0,0),c(3,0))));
        assertThrows(IllegalArgumentException.class,
            ()->b.useAbility(10,"retreat",List.of(c(0,1),c(1,1))));
        b.useAbility(10,"retreat",List.of(c(0,0),c(1,0)));
        assertEquals(1,b.view(10).yourShips().get(0).row());
        assertEquals(1,b.view(11).yourShips().get(0).row());
        assertTrue(b.view(11).shotsReceived().isEmpty());
        b.fire(11,c(0,0));
        assertFalse(b.view(11).shotsFired().get(0).hit());
        b.fire(10,c(7,7));
        b.fire(11,c(1,0));
        assertTrue(b.view(11).shotsFired().get(1).hit());
    }

    @Test void noAbilityRepeatsAndInvalidAreaNeverConsumesUseOrRevision() {
        var b=battle(8);
        assertThrows(IllegalArgumentException.class,()->b.useAbility(10,"double-shot",List.of(c(0,3))));
        assertEquals(1,b.view(10).revision());
        b.useAbility(10,"double-shot",List.of(c(6,1),c(6,2)));
        b.fire(11,c(7,7));
        assertThrows(IllegalStateException.class,
            ()->b.useAbility(10,"double-shot",List.of(c(5,1),c(5,2))));
        assertEquals(0,b.view(10).yourAbilities().get(0).usesRemaining());
    }

    @Test void allAbilitiesDeniedInOrdinaryClassicAndSpectatorsCannotReadEitherPlayersView() {
        var mode=GameModePresets.classic();
        var b=new BattleRound("ABC123",mode,10,11,Board.random(mode),Board.random(mode));
        assertTrue(b.view(10).yourAbilities().isEmpty());
        assertThrows(IllegalStateException.class,
            ()->b.useAbility(10,"radar",List.of(c(1,1))));
        assertThrows(IllegalArgumentException.class,()->b.view(99));
        assertThrows(IllegalArgumentException.class,
            ()->b.useAbility(99,"radar",List.of(c(1,1))));
    }
}
