package com.FGInteractive.BatalhaNaval.match;

import static org.junit.jupiter.api.Assertions.*;
import com.FGInteractive.BatalhaNaval.match.dto.PlaceFleetRequest.ShipPlacement;
import com.FGInteractive.BatalhaNaval.match.mode.GameModePresets;
import com.FGInteractive.BatalhaNaval.match.model.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BattleTerminationTests {
    private static final Instant START = Instant.parse("2026-01-01T12:00:00Z");
    private static final Duration GRACE = Duration.ofSeconds(120);

    private BattleRound newGame() {
        var mode = GameModePresets.quick();
        var first = Board.from(mode, List.of(
            new ShipPlacement("BATTLESHIP",0,0,Orientation.HORIZONTAL),
            new ShipPlacement("CRUISER",1,0,Orientation.HORIZONTAL),
            new ShipPlacement("DESTROYER",2,0,Orientation.HORIZONTAL)));
        var second = Board.from(mode, List.of(
            new ShipPlacement("BATTLESHIP",4,0,Orientation.HORIZONTAL),
            new ShipPlacement("CRUISER",5,0,Orientation.HORIZONTAL),
            new ShipPlacement("DESTROYER",6,0,Orientation.HORIZONTAL)));
        return new BattleRound("ABCD23",mode,10,11,first,second);
    }

    @Test void voluntaryForfeitRecordsWinnerReasonAndIsIdempotentForLoser() {
        var battle = newGame();
        assertTrue(battle.forfeit(10));
        assertTrue(battle.finished());
        assertEquals("ABANDONED", battle.view(10).finishReason());
        assertEquals(11L,battle.view(10).winnerId().longValue());
        assertEquals("FINISHED",battle.view(11).status());
        assertEquals(2,battle.view(11).revision());
        assertFalse(battle.forfeit(10));
        assertThrows(IllegalStateException.class,()->battle.forfeit(11));
        assertThrows(IllegalStateException.class,()->battle.fire(11,new Cell(0,0)));
    }

    @Test void disconnectDoesNotForfeitBeforeDeadlineAndRecoveryCancelsTimer() {
        var battle = newGame();
        battle.setPlayerConnected(10, false, START, GRACE);
        assertEquals(START.plus(GRACE),battle.view(11).reconnectDeadlines().get(10L));
        assertFalse(battle.resolveDisconnects(START.plusSeconds(119)));
        assertEquals("PLAYING",battle.view(11).status());
        battle.setPlayerConnected(10,true,START.plusSeconds(119),GRACE);
        assertTrue(battle.view(11).reconnectDeadlines().isEmpty());
        assertFalse(battle.resolveDisconnects(START.plusSeconds(1000)));
        assertEquals("PLAYING",battle.view(10).status());
    }

    @Test void aSingleTimedOutPlayerLosesOnlyIfOpponentIsConnected() {
        var battle = newGame();
        battle.setPlayerConnected(11,false,START,GRACE);
        assertFalse(battle.resolveDisconnects(START.plusSeconds(119)));
        assertTrue(battle.resolveDisconnects(START.plusSeconds(120)));
        assertEquals("DISCONNECT_TIMEOUT",battle.view(11).finishReason());
        assertEquals(10L,battle.view(11).winnerId().longValue());
        assertTrue(battle.view(10).reconnectDeadlines().isEmpty());
        assertFalse(battle.resolveDisconnects(START.plusSeconds(999)));
    }

    @Test void bothOfflineDoNotGiveArbitraryVictoryAndEventuallyCancel() {
        var battle = newGame();
        battle.setPlayerConnected(10,false,START,GRACE);
        battle.setPlayerConnected(11,false,START.plusSeconds(30),GRACE);
        assertFalse(battle.resolveDisconnects(START.plusSeconds(125)));
        assertEquals("PLAYING",battle.view(10).status());
        assertTrue(battle.resolveDisconnects(START.plusSeconds(150)));
        assertNull(battle.view(11).winnerId());
        assertNull(battle.view(11).turnPlayerId());
        assertEquals("BOTH_DISCONNECTED",battle.view(10).finishReason());
    }

    @Test void reconnectStartsNoNewGraceAndMultipleDisconnectsDoNotResetCountdown() {
        var battle = newGame();
        battle.setPlayerConnected(10,false,START,GRACE);
        assertFalse(battle.setPlayerConnected(10,false,START.plusSeconds(70),GRACE));
        assertEquals(START.plus(GRACE),battle.view(10).reconnectDeadlines().get(10L));
        battle.setPlayerConnected(10,true,START.plusSeconds(100),GRACE);
        battle.setPlayerConnected(10,false,START.plusSeconds(101),GRACE);
        assertEquals(START.plusSeconds(221),
            battle.view(10).reconnectDeadlines().get(10L));
        assertFalse(battle.resolveDisconnects(START.plusSeconds(121)));
    }

    @Test void normalFleetVictoryHasItsOwnEndReasonAndCannotBeOverwritten() {
        var battle = newGame();
        // Force a voluntary finish; old timers and subsequent clocks must not overwrite it.
        battle.setPlayerConnected(10,false,START,GRACE);
        battle.forfeit(11);
        assertEquals("ABANDONED",battle.view(10).finishReason());
        assertEquals(10L,battle.view(10).winnerId().longValue());
        assertFalse(battle.resolveDisconnects(START.plusSeconds(3600)));
    }
}
