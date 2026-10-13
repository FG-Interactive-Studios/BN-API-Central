package com.FGInteractive.BatalhaNaval.match;

import static org.junit.jupiter.api.Assertions.*;

import com.FGInteractive.BatalhaNaval.match.dto.PlaceFleetRequest.ShipPlacement;
import com.FGInteractive.BatalhaNaval.match.mode.*;
import com.FGInteractive.BatalhaNaval.match.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TriangularTileGeometryTests {
    @Test void eachTriangleIsARealCellWithThreeEdgeNeighborsAtMost() {
        var geometry = new TriangularTileGeometry(8);
        assertEquals("TRIANGULAR_TILES", geometry.kind());
        assertEquals(8, geometry.rows());
        assertEquals(15, geometry.columns());
        assertEquals(64, geometry.cells().size());
        assertEquals(64, geometry.tiles().size());
        for (int r=0;r<8;r++) {
            int row=r;
            assertEquals(2*r+1, geometry.cells().stream()
                .filter(c -> c.row()==row).count());
        }
        assertEquals(List.of(new Cell(1,1)), geometry.neighbors(new Cell(0,0)));
        assertEquals(Set.of(new Cell(1,0),new Cell(1,2),new Cell(0,0)),
            Set.copyOf(geometry.neighbors(new Cell(1,1))));
        assertFalse(geometry.contains(new Cell(1,3)));
        assertFalse(geometry.contains(new Cell(8,0)));
        assertFalse(geometry.contains(new Cell(-1,0)));
        for (Cell cell : geometry.cells()) {
            List<Cell> neighbors=geometry.neighbors(cell);
            assertTrue(neighbors.size()<=3);
            assertEquals(neighbors.size(),new HashSet<>(neighbors).size());
            assertTrue(neighbors.stream().allMatch(geometry::contains));
            for(Cell other:neighbors)
                assertTrue(geometry.neighbors(other).contains(cell),
                    "Adjacency must be symmetric at "+cell+" and "+other);
        }
    }

    @Test void tilePolygonsHaveCorrectGeometryAndShareEntireEdges() {
        var geometry = new TriangularTileGeometry(5);
        var tiles = geometry.tiles();
        assertEquals(25,tiles.size());
        Map<Cell,TileShape> byCell = new HashMap<>();
        for(TileShape tile:tiles) {
            assertTrue(geometry.contains(tile.cell()));
            assertEquals(3,tile.vertices().size());
            assertEquals(tile.cell().col()%2==0?"TRIANGLE_UP":"TRIANGLE_DOWN",
                tile.shape());
            assertNull(byCell.put(tile.cell(),tile));
            for(TileShape.Point p:tile.vertices()) {
                assertTrue(p.x()>=0 && p.x()<=5);
                assertTrue(p.y()>=0 && p.y()<=5*Math.sqrt(3)/2);
            }
        }
        for(Cell cell:geometry.cells()) {
            for(Cell other:geometry.cells()) {
                if(cell.equals(other))continue;
                Set<TileShape.Point> shared = new HashSet<>(byCell.get(cell).vertices());
                shared.retainAll(byCell.get(other).vertices());
                boolean adjacent=geometry.neighbors(cell).contains(other);
                assertEquals(adjacent, shared.size()==2,
                    "Only edge-sharing triangles are adjacent: "+cell+" / "+other);
            }
        }
        assertThrows(UnsupportedOperationException.class,()->tiles.clear());
        assertThrows(UnsupportedOperationException.class,
            ()->tiles.get(0).vertices().clear());
    }

    @Test void triangularShipPathsFollowDeclaredAxesAndNoSquareGridShortcuts() {
        var mode=GameModePresets.triangleTiles();
        assertEquals(List.of(Orientation.TRI_RIGHT,Orientation.TRI_DOWN_LEFT,
                Orientation.TRI_DOWN_RIGHT),mode.placement().directions());
        assertFalse(mode.placement().supports(new RectangularGeometry(8,8)));
        assertFalse(new LinearPlacementRule().supports(mode.geometry()));
        assertThrows(IllegalArgumentException.class,()->new GameModeDefinition(
            "wrong-triangle","Wrong",mode.geometry(),mode.fleet(),
            new LinearPlacementRule(),mode.rules()));

        var fleet=List.of(
            new ShipPlacement("BATTLESHIP",5,0,Orientation.TRI_RIGHT),
            new ShipPlacement("CRUISER",1,0,Orientation.TRI_DOWN_RIGHT),
            new ShipPlacement("DESTROYER",3,0,Orientation.TRI_DOWN_LEFT));
        var board=Board.from(mode,fleet);
        assertEquals(9,board.occupied().size());
        for(ShipPlacement ship:fleet) {
            List<Cell> cells=mode.placement().footprint(
                ship,mode.fleet().require(ship.type()),mode.geometry());
            for(int i=1;i<cells.size();i++)
                assertTrue(mode.geometry().neighbors(cells.get(i-1)).contains(cells.get(i)));
        }

        var invalid=new ArrayList<>(fleet);
        invalid.set(0,new ShipPlacement("BATTLESHIP",0,0,Orientation.TRI_RIGHT));
        assertThrows(IllegalArgumentException.class,()->Board.from(mode,invalid));
        invalid.set(0,new ShipPlacement("BATTLESHIP",5,0,Orientation.HORIZONTAL));
        assertThrows(IllegalArgumentException.class,()->Board.from(mode,invalid));
        invalid.set(0,new ShipPlacement("BATTLESHIP",5,0,Orientation.TRI_RIGHT));
        invalid.set(1,new ShipPlacement("CRUISER",5,0,Orientation.TRI_RIGHT));
        assertThrows(IllegalArgumentException.class,()->Board.from(mode,invalid));
    }

    @Test void allBoardTypesProvideValidTilesAndRandomCompleteFleets() {
        for(GameModeDefinition mode:List.of(GameModePresets.classic(),
                GameModePresets.quick(),GameModePresets.triangular(),
                GameModePresets.triangleTiles())) {
            var info=mode.summary().geometry();
            assertEquals(info.cells().size(),info.tiles().size());
            assertEquals(new HashSet<>(info.cells()).size(),info.tiles().size());
            assertEquals(mode.placement().directions(),mode.summary().placementDirections());
            for(int i=0;i<80;i++) {
                Board random=Board.random(mode);
                assertEquals(mode.fleet().occupiedCells(),random.occupied().size());
                assertEquals(random.occupied(),Board.from(mode,random.ships()).occupied());
            }
        }
        assertEquals("SQUARE",GameModePresets.triangular()
            .summary().geometry().tiles().get(0).shape());
        assertEquals("TRIANGLE_UP",GameModePresets.triangleTiles()
            .summary().geometry().tiles().get(0).shape());
    }

    @Test void battleUsesTriangularCoordinatesForValidShotsAndRejectsNonPlayableCells() {
        GameModeDefinition mode=GameModePresets.triangleTiles();
        BattleRound battle=new BattleRound("ABCD23",mode,10,11,
            Board.random(mode),Board.random(mode));
        assertThrows(IllegalArgumentException.class,()->battle.fire(10,new Cell(1,3)));
        assertThrows(IllegalArgumentException.class,()->battle.fire(10,new Cell(8,0)));
        assertEquals(1,battle.view(10).revision());
        Cell target=mode.geometry().cells().get(0);
        battle.fire(10,target);
        assertEquals(2,battle.view(10).revision());
        assertEquals(target.row(),battle.view(10).shotsFired().get(0).row());
        assertEquals(target.col(),battle.view(10).shotsFired().get(0).col());
        assertTrue(battle.view(11).shotsReceived().stream()
            .anyMatch(s->s.row()==target.row()&&s.col()==target.col()));
    }
}
