package com.FGInteractive.BatalhaNaval.match;

import static org.junit.jupiter.api.Assertions.*;
import com.FGInteractive.BatalhaNaval.match.dto.PlaceFleetRequest.ShipPlacement;
import com.FGInteractive.BatalhaNaval.match.mode.*;
import com.FGInteractive.BatalhaNaval.match.model.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deterministic unit tests: no sleeps, network, or real wall-clock waits. */
class GddMatchRulesTests {
    static final Instant START=Instant.parse("2026-11-22T12:00:00Z");

    private static Board board(GameModeDefinition mode,int row) {
        return Board.from(mode,List.of(
            new ShipPlacement("CARRIER",row,0,Orientation.HORIZONTAL),
            new ShipPlacement("DESTROYER",row+1,0,Orientation.HORIZONTAL),
            new ShipPlacement("SEARCH",row+2,0,Orientation.HORIZONTAL)));
    }
    private static BattleRound timed(boolean powers) {
        GameModeDefinition mode=powers?GameModePresets.classicPowers():GameModePresets.classic();
        return new BattleRound("ABC234",mode,10,11,board(mode,0),board(mode,4),
            START,GddMatchRules.TURN,GddMatchRules.MATCH);
    }
    @Test void baseModesHaveExplicitGddDeadlinesAndThreeShips() {
        assertEquals(Duration.ofSeconds(30),GddMatchRules.PREPARATION);
        assertEquals(Duration.ofSeconds(20),GddMatchRules.TURN);
        assertEquals(Duration.ofMinutes(10),GddMatchRules.MATCH);
        assertEquals(Duration.ofSeconds(15),GddMatchRules.RECONNECT);
        for(boolean powers:List.of(false,true)) {
            var game=timed(powers);
            assertEquals(START.plusSeconds(20),game.view(10).turnDeadlineAt());
            assertEquals(START.plusSeconds(600),game.view(11).matchDeadlineAt());
            assertEquals(3,game.view(10).yourShips().size());
            assertEquals(0,game.view(10).yourConsecutiveMissedTurns());
        }
    }

    @Test void preparationExpiresOncePreservesSubmittedFleetAndAutoConfirmsMissing() {
        var mode=GameModePresets.classic();
        var round=new PreparationRound("ABC234",10,11,mode,START,GddMatchRules.PREPARATION);
        var submitted=board(mode,0);
        round.place(10,submitted);
        long revision=round.view(10).revision();
        assertEquals(START.plusSeconds(30),round.view(11).placementDeadlineAt());
        assertFalse(round.completeExpired(START.plusSeconds(29)));
        assertFalse(round.bothConfirmed());
        assertTrue(round.completeExpired(START.plusSeconds(30)));
        assertTrue(round.bothConfirmed());
        assertEquals(submitted.ships(),round.confirmedBoard(10).ships());
        assertEquals(3,round.confirmedBoard(11).ships().size());
        assertEquals(8,round.confirmedBoard(11).occupied().size());
        assertEquals(revision+1,round.view(10).revision());
        assertFalse(round.completeExpired(START.plusSeconds(60)));
        assertEquals(revision+1,round.view(11).revision());
        assertTrue(round.view(10).opponentConfirmed());
        assertTrue(round.view(11).opponentConfirmed());
    }

    @Test void turnExpiresAtExactBoundaryAndThreeOwnMissesLoseByAbandonment() {
        var game=timed(false);
        assertFalse(game.resolveTimed(START.plusSeconds(19)));
        assertThrows(IllegalStateException.class,
            ()->game.fireAt(10,new Cell(4,0),START.plusSeconds(20)));
        assertTrue(game.resolveTimed(START.plusSeconds(20)));
        assertEquals(1,game.view(10).yourConsecutiveMissedTurns());
        assertEquals(11L,game.view(10).turnPlayerId());
        assertEquals(START.plusSeconds(40),game.view(11).turnDeadlineAt());
        assertTrue(game.resolveTimed(START.plusSeconds(40)));
        assertTrue(game.resolveTimed(START.plusSeconds(60)));
        assertTrue(game.resolveTimed(START.plusSeconds(80)));
        assertTrue(game.resolveTimed(START.plusSeconds(100)));
        assertEquals("FINISHED",game.view(10).status());
        assertEquals("TURN_TIMEOUT_FORFEIT",game.view(10).finishReason());
        assertEquals(11L,game.view(10).winnerId());
        assertNull(game.view(11).turnDeadlineAt());
        assertFalse(game.resolveTimed(START.plusSeconds(120)));
    }

    @Test void legitimateAttackResetsOnlyAttackersTimeoutStreak() {
        var game=timed(false);
        game.resolveTimed(START.plusSeconds(20)); // host misses first turn
        game.fireAt(11,new Cell(7,7),START.plusSeconds(21)); // miss
        assertEquals(1,game.missedTurns(10));
        assertEquals(0,game.missedTurns(11));
        game.fireAt(10,new Cell(4,0),START.plusSeconds(22)); // actual shot
        assertEquals(0,game.missedTurns(10));
        assertEquals(START.plusSeconds(42),game.view(11).turnDeadlineAt());
    }

    @Test void timeLimitUsesRemainingShipsAndDeclaresDrawOnTie() {
        var draw=timed(false);
        assertTrue(draw.resolveTimed(START.plusSeconds(600)));
        assertEquals("TIME_LIMIT_DRAW",draw.view(10).finishReason());
        assertNull(draw.view(11).winnerId());

        var win=timed(false);
        win.fireAt(10,new Cell(6,0),START.plusSeconds(2)); // sink guest search
        win.fireAt(11,new Cell(7,7),START.plusSeconds(3)); // miss
        assertTrue(win.resolveTimed(START.plusSeconds(600)));
        assertEquals("TIME_LIMIT",win.view(10).finishReason());
        assertEquals(10L,win.view(11).winnerId());
        assertFalse(win.resolveTimed(START.plusSeconds(601)));
        assertThrows(IllegalStateException.class,
            ()->win.fireAt(11,new Cell(0,0),START.plusSeconds(602)));
    }

    @Test void reconnectPausesBothDeadlinesAndTheyResumeWithoutLosingElapsedTime() {
        var game=timed(false);
        assertTrue(game.setPlayerConnected(10,false,START.plusSeconds(5),Duration.ofSeconds(15)));
        assertTrue(game.view(10).paused());
        assertFalse(game.resolveTimed(START.plusSeconds(40)));
        assertTrue(game.setPlayerConnected(10,true,START.plusSeconds(13),Duration.ofSeconds(15)));
        assertFalse(game.view(11).paused());
        assertEquals(START.plusSeconds(28),game.view(10).turnDeadlineAt());
        assertEquals(START.plusSeconds(608),game.view(11).matchDeadlineAt());
        assertFalse(game.resolveTimed(START.plusSeconds(27)));
        assertTrue(game.resolveTimed(START.plusSeconds(28)));
        assertEquals(11L,game.view(10).turnPlayerId());
    }

    @Test void disconnectTimeoutWinsOrCancelsWithoutTimerOverwritingResult() {
        var single=timed(false);
        single.setPlayerConnected(10,false,START,Duration.ofSeconds(15));
        assertFalse(single.resolveDisconnects(START.plusSeconds(14)));
        assertTrue(single.resolveDisconnects(START.plusSeconds(15)));
        assertEquals("DISCONNECT_TIMEOUT",single.view(10).finishReason());
        assertEquals(11L,single.view(10).winnerId());
        assertFalse(single.resolveTimed(START.plusSeconds(600)));

        var both=timed(false);
        both.setPlayerConnected(10,false,START,Duration.ofSeconds(15));
        both.setPlayerConnected(11,false,START,Duration.ofSeconds(15));
        assertTrue(both.resolveDisconnects(START.plusSeconds(15)));
        assertNull(both.view(10).winnerId());
        assertEquals("BOTH_DISCONNECTED",both.view(11).finishReason());
    }

    @Test void fourAbilityPointsArePrivateAndEarnedByValidShotsOnly() {
        var game=timed(true);
        var own=game.view(10);
        var enemy=game.view(11);
        assertEquals(4,own.yourAbilityPointCells().size());
        assertEquals(4,enemy.yourAbilityPointCells().size());
        assertTrue(own.yourAbilityPointCells().stream()
            .noneMatch(board(GameModePresets.classicPowers(),0).occupied()::contains));
        assertTrue(enemy.yourAbilityPointCells().stream()
            .noneMatch(board(GameModePresets.classicPowers(),4).occupied()::contains));
        assertEquals(0,own.yourAbilityPointsEarned());
        assertTrue(own.yourAbilities().stream()
            .filter(a->a.id().equals("interference")).findFirst().orElseThrow().locked());
        assertThrows(IllegalStateException.class,
            ()->game.useAbilityAt(10,"interference",List.of(),START.plusSeconds(1)));

        var point=enemy.yourAbilityPointCells().get(0);
        game.fireAt(10,point,START.plusSeconds(1));
        assertEquals(1,game.view(10).yourAbilityPointsEarned());
        assertTrue(game.view(10).shotsFired().get(0).hit()==false);
        game.useAbilityAt(11,"radar",List.of(new Cell(0,0)),START.plusSeconds(2));
        game.useAbilityAt(10,"interference",List.of(),START.plusSeconds(3));
        assertEquals(0,game.view(10).yourAbilityPointsEarned());
        assertEquals(1,game.view(10).yourAbilities().stream()
            .filter(a->a.id().equals("interference")).findFirst().orElseThrow().usesRemaining()==0?1:0);
    }

    @Test void classicWithoutPowersHasNoPointsOrAbilityInventory() {
        var game=timed(false);
        assertTrue(game.view(10).yourAbilityPointCells().isEmpty());
        assertTrue(game.view(11).yourAbilityPointCells().isEmpty());
        assertTrue(game.view(10).yourAbilities().isEmpty());
    }
}
