package com.FGInteractive.BatalhaNaval.match;

import static org.junit.jupiter.api.Assertions.*;
import com.FGInteractive.BatalhaNaval.match.dto.PlaceFleetRequest.ShipPlacement;
import com.FGInteractive.BatalhaNaval.match.mode.*;
import com.FGInteractive.BatalhaNaval.match.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BattleRoundTests {
    private static GameModeDefinition duel(TurnRule turn) {
        return new GameModeDefinition("duel", "Duel", new RectangularGeometry(4,4),
            new FleetDefinition(List.of(new ShipDefinition("SCOUT", 1))),
            new LinearPlacementRule(),
            Map.of(
                RuleSlot.MOVEMENT, new DeclaredRule("stationary", RuleSlot.MOVEMENT),
                RuleSlot.ABILITY, new DeclaredRule("disabled", RuleSlot.ABILITY),
                RuleSlot.ATTACK, new StandardShotRule(),
                RuleSlot.TURN, turn,
                RuleSlot.VICTORY, new AllShipsSunkRule()));
    }
    private static Board board(GameModeDefinition mode, int row, int col) {
        return Board.from(mode, List.of(
            new ShipPlacement("SCOUT", row, col, Orientation.HORIZONTAL)));
    }

    @Test void hostStartsAndStandardShotAlternatesOnMissAndHit() {
        var mode=duel(new AlternatingTurnRule());
        var round=new BattleRound("ABCD23", mode, 10, 11,
            board(mode,0,0),board(mode,1,1));
        assertEquals(10L,round.view(11).turnPlayerId().longValue());
        assertEquals("PLAYING",round.view(10).status());
        assertThrows(IllegalStateException.class,()->round.fire(11,new Cell(0,0)));
        assertThrows(IllegalArgumentException.class,()->round.fire(10,new Cell(4,0)));
        assertThrows(IllegalArgumentException.class,()->round.fire(10,new Cell(-1,0)));
        assertEquals(1,round.view(10).revision());

        round.fire(10,new Cell(2,2)); // miss
        assertFalse(round.view(10).shotsFired().get(0).hit());
        assertEquals(11L,round.view(10).turnPlayerId().longValue());
        assertThrows(IllegalStateException.class,()->round.fire(10,new Cell(1,1)));
        round.fire(11,new Cell(0,3)); // miss
        assertThrows(IllegalStateException.class,()->round.fire(10,new Cell(2,2))); // replay
        round.fire(10,new Cell(1,1)); // sink SCOUT, game over
        assertEquals("FINISHED",round.view(10).status());
        assertEquals(10L,round.view(10).winnerId().longValue());
        assertNull(round.view(11).turnPlayerId());
        assertEquals("SCOUT",round.view(10).shotsFired().get(1).sunkShip());
        assertTrue(round.view(11).shotsReceived().get(1).hit());
        assertEquals(List.of(new ShipPlacement("SCOUT",0,0,Orientation.HORIZONTAL)),
            round.view(10).yourShips());
        assertEquals(List.of(new ShipPlacement("SCOUT",1,1,Orientation.HORIZONTAL)),
            round.view(11).yourShips());
        assertThrows(IllegalStateException.class,()->round.fire(11,new Cell(0,0)));
    }

    @Test void attackingWholeFleetEndsGameInAllThreeModesWithoutLeakingPrivateBoard() {
        for (GameModeDefinition mode:List.of(
                GameModePresets.classic(),GameModePresets.quick(),GameModePresets.triangular())) {
            var host=Board.random(mode);
            var guest=Board.random(mode);
            var game=new BattleRound("ABCDE2",mode,10,11,host,guest);
            var targets=new ArrayList<>(guest.occupied());
            targets.sort(Comparator.comparingInt(Cell::row).thenComparingInt(Cell::col));
            var misses=new ArrayList<>(mode.geometry().cells());
            misses.removeAll(host.occupied());
            assertTrue(misses.size()>targets.size());
            for(int i=0;i<targets.size();i++) {
                game.fire(10,targets.get(i));
                if(i < targets.size()-1) game.fire(11,misses.get(i));
            }
            assertTrue(game.finished());
            assertEquals(10L,game.view(11).winnerId().longValue());
            assertEquals(mode.fleet().occupiedCells(),game.view(10).shotsFired().size());
            assertEquals(mode.fleet().occupiedCells()-1,game.view(11).shotsFired().size());
            assertEquals(host.ships(),game.view(10).yourShips());
            assertEquals(guest.ships(),game.view(11).yourShips());
            assertNotEquals(host.ships(),game.view(11).yourShips());
            assertTrue(game.view(10).shotsReceived().stream().noneMatch(s->s.hit()));
            assertEquals("FINISHED",game.view(11).status());
        }
    }

    @Test void customTurnMechanicCanKeepTurnAfterHitWithoutChangingBattleRound() {
        TurnRule hitAgain = new TurnRule() {
            @Override public String id() { return "repeat-on-hit"; }
            @Override public long nextPlayer(long attacker,long defender,ShotImpact outcome) {
                return outcome.hit() ? attacker : defender;
            }
        };
        var mode=duel(hitAgain);
        var game=new BattleRound("ABCD23",mode,10,11,board(mode,0,0),board(mode,1,1));
        game.fire(10,new Cell(1,1)); // finishes immediately; turn no longer matters.
        assertEquals(10L,game.view(10).winnerId().longValue());

        // Compose with a two-cell fleet, so the first hit does not finish.
        var extended=new GameModeDefinition("extended", "Extended",new RectangularGeometry(4,4),
            new FleetDefinition(List.of(new ShipDefinition("SCOUT",2))),
            new LinearPlacementRule(),mode.rules());
        var b1=Board.from(extended,List.of(
            new ShipPlacement("SCOUT",0,0,Orientation.HORIZONTAL)));
        var b2=Board.from(extended,List.of(
            new ShipPlacement("SCOUT",1,0,Orientation.HORIZONTAL)));
        var battle=new BattleRound("ABCD23",extended,10,11,b1,b2);
        battle.fire(10,new Cell(1,0));
        assertEquals(10L,battle.view(10).turnPlayerId().longValue());
        assertTrue(battle.view(10).shotsFired().get(0).hit());
        assertNull(battle.view(10).shotsFired().get(0).sunkShip());
        battle.fire(10,new Cell(1,1));
        assertEquals(10L,battle.view(11).winnerId().longValue());
    }

    @Test void rejectsInvalidAttackAndVictoryRuleAtModeCompositionTime() {
        var standard=GameModePresets.classic();
        var rules=new EnumMap<RuleSlot,RuleModule>(standard.rules());
        rules.put(RuleSlot.ATTACK,new DeclaredRule("pretend-power",RuleSlot.ATTACK));
        assertThrows(IllegalArgumentException.class,()->new GameModeDefinition(
            "invalid", "Invalid", standard.geometry(),standard.fleet(),
            standard.placement(),rules));
    }
}
