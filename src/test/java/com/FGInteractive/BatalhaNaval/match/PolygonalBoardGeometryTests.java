package com.FGInteractive.BatalhaNaval.match;

import static org.junit.jupiter.api.Assertions.*;
import com.FGInteractive.BatalhaNaval.match.dto.PlaceFleetRequest.ShipPlacement;
import com.FGInteractive.BatalhaNaval.match.mode.*;
import com.FGInteractive.BatalhaNaval.match.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Geometry checks that protect both the shooting grid and visual polygons. */
class PolygonalBoardGeometryTests {
    private static final double EPSILON = 1e-8;

    private boolean same(TileShape.Point a, TileShape.Point b) {
        return Math.hypot(a.x()-b.x(),a.y()-b.y()) < EPSILON;
    }

    private int sharedVertices(TileShape a, TileShape b) {
        int count=0;
        for (var p:a.vertices()) {
            if (b.vertices().stream().anyMatch(v->same(p,v)))count++;
        }
        return count;
    }

    private double sideLength(TileShape.Point a,TileShape.Point b) {
        return Math.hypot(a.x()-b.x(),a.y()-b.y());
    }

    @Test void hexagonIsARealSixSidedTileWithSymmetricEdgeNeighbors() {
        var geo=new HexagonalGeometry(4);
        assertEquals("HEXAGONAL_TILES",geo.kind());
        assertEquals(9,geo.rows());
        assertEquals(9,geo.columns());
        assertEquals(61,geo.cells().size());
        assertEquals(61,geo.tiles().size());
        assertEquals(6,geo.neighbors(new Cell(4,4)).size());
        assertFalse(geo.contains(new Cell(0,0)));
        assertTrue(geo.contains(new Cell(0,4)));
        assertTrue(geo.contains(new Cell(8,4)));
        assertTrue(geo.contains(new Cell(4,0)));
        assertTrue(geo.contains(new Cell(4,8)));
        assertFalse(geo.contains(new Cell(8,8)));
        for(var cell:geo.cells()) {
            var neighbors=geo.neighbors(cell);
            assertEquals(neighbors.size(),new HashSet<>(neighbors).size());
            for(var next:neighbors) {
                assertTrue(geo.contains(next));
                assertTrue(geo.neighbors(next).contains(cell));
            }
        }
        var shapes=new HashMap<Cell,TileShape>();
        geo.tiles().forEach(tile -> {
            assertEquals("HEXAGON",tile.shape());
            assertEquals(6,tile.vertices().size());
            var v=tile.vertices();
            double side=sideLength(v.get(0),v.get(1));
            for(int i=0;i<6;i++) {
                assertEquals(side,sideLength(v.get(i),v.get((i+1)%6)),EPSILON);
            }
            assertNull(shapes.put(tile.cell(),tile));
        });
        for(var a:geo.cells())for(var b:geo.cells()) {
            if(a.equals(b))continue;
            assertEquals(geo.neighbors(a).contains(b),sharedVertices(shapes.get(a),shapes.get(b))==2,
                "Hexagons should share an edge exactly when neighboring: "+a+" "+b);
        }
    }

    @Test void octagonsHaveEightRegularEdgesAndSquareGapsAreNotCells() {
        var geo=new OctagonalGeometry(8,8);
        assertEquals("OCTAGONAL_TILES",geo.kind());
        assertEquals(64,geo.cells().size());
        assertEquals(64,geo.tiles().size());
        assertEquals(Set.of(new Cell(2,3),new Cell(4,3),new Cell(3,2),new Cell(3,4)),
            Set.copyOf(geo.neighbors(new Cell(3,3))));
        assertFalse(geo.neighbors(new Cell(3,3)).contains(new Cell(4,4)));
        var byCell=new HashMap<Cell,TileShape>();
        for (var tile:geo.tiles()) {
            assertEquals("OCTAGON",tile.shape());
            assertEquals(8,tile.vertices().size());
            var v=tile.vertices();
            double length=sideLength(v.get(0),v.get(1));
            for(int i=0;i<8;i++)assertEquals(length,
                sideLength(v.get(i),v.get((i+1)%8)),EPSILON);
            assertNull(byCell.put(tile.cell(),tile));
        }
        assertEquals(2,sharedVertices(byCell.get(new Cell(3,3)),
            byCell.get(new Cell(3,4))));
        assertEquals(0,sharedVertices(byCell.get(new Cell(3,3)),
            byCell.get(new Cell(4,4))));
        assertThrows(IllegalArgumentException.class,()->new OctagonalGeometry(1,8));
        assertThrows(IllegalArgumentException.class,()->new HexagonalGeometry(1));
    }

    @Test void shipsStayStraightAlongAllRequestedAxesAndCannotEscapeBoard() {
        var hex=GameModePresets.hexagons();
        var oct=GameModePresets.octagons();
        assertEquals(List.of(Orientation.HEX_VERTICAL,Orientation.HEX_DIAGONAL_UP,
            Orientation.HEX_DIAGONAL_DOWN),hex.placement().directions());
        assertEquals(List.of(Orientation.HORIZONTAL,Orientation.VERTICAL,
            Orientation.DIAGONAL_DOWN,Orientation.DIAGONAL_UP),oct.placement().directions());
        var hexFleet=List.of(
            new ShipPlacement("BATTLESHIP",4,4,Orientation.HEX_VERTICAL),
            new ShipPlacement("CRUISER",4,0,Orientation.HEX_DIAGONAL_DOWN),
            new ShipPlacement("DESTROYER",6,1,Orientation.HEX_DIAGONAL_UP));
        assertEquals(9,Board.from(hex,hexFleet).occupied().size());
        var octFleet=List.of(
            new ShipPlacement("BATTLESHIP",0,0,Orientation.HORIZONTAL),
            new ShipPlacement("CRUISER",2,2,Orientation.VERTICAL),
            new ShipPlacement("DESTROYER",5,2,Orientation.DIAGONAL_UP));
        assertEquals(9,Board.from(oct,octFleet).occupied().size());
        assertEquals(List.of(new Cell(5,2),new Cell(4,3)),
            oct.placement().footprint(octFleet.get(2),oct.fleet().require("DESTROYER"),
                oct.geometry()));
        var octDown=new ShipPlacement("DESTROYER",5,2,Orientation.DIAGONAL_DOWN);
        assertEquals(List.of(new Cell(5,2),new Cell(6,3)),
            oct.placement().footprint(octDown,oct.fleet().require("DESTROYER"),oct.geometry()));

        assertThrows(IllegalArgumentException.class,()->Board.from(hex,
            List.of(hexFleet.get(0),hexFleet.get(1),
                new ShipPlacement("DESTROYER",6,1,Orientation.HORIZONTAL))));
        assertThrows(IllegalArgumentException.class,()->Board.from(oct,
            List.of(octFleet.get(0),octFleet.get(1),
                new ShipPlacement("DESTROYER",7,7,Orientation.DIAGONAL_DOWN))));
        assertThrows(IllegalArgumentException.class,()->Board.from(hex,
            List.of(hexFleet.get(0),hexFleet.get(1),
                new ShipPlacement("DESTROYER",4,4,Orientation.HEX_VERTICAL))));
        assertThrows(IllegalArgumentException.class,()->new GameModeDefinition(
            "wrong-hex", "Wrong",hex.geometry(),hex.fleet(),new LinearPlacementRule(),
            hex.rules()));
        assertThrows(IllegalArgumentException.class,()->new GameModeDefinition(
            "wrong-oct", "Wrong",oct.geometry(),oct.fleet(),new HexagonalPlacementRule(),
            oct.rules()));
    }

    @Test void generatedFleetsAndCombatWorkForBothGeometries() {
        for(var mode:List.of(GameModePresets.hexagons(),GameModePresets.octagons())) {
            assertEquals(9,mode.fleet().occupiedCells());
            assertEquals(mode.geometry().cells().size(),mode.summary().geometry().tiles().size());
            assertEquals(3,mode.fleet().ships().size());
            for(int i=0;i<120;i++) {
                var board=Board.random(mode);
                assertEquals(9,board.occupied().size());
                assertEquals(board.occupied(),Board.from(mode,board.ships()).occupied());
                assertTrue(board.occupied().stream().allMatch(mode.geometry()::contains));
            }
            var round=new BattleRound("ABCD23",mode,10,11,
                Board.random(mode),Board.random(mode));
            var target=mode.geometry().cells().get(0);
            round.fire(10,target);
            assertEquals(2,round.view(10).revision());
            assertEquals(target.row(),round.view(10).shotsFired().get(0).row());
            assertEquals(target.col(),round.view(11).shotsReceived().get(0).col());
        }
    }
}
