package com.FGInteractive.BatalhaNaval.match.mode;

import com.FGInteractive.BatalhaNaval.match.dto.PlaceFleetRequest.ShipPlacement;
import com.FGInteractive.BatalhaNaval.match.dto.RadarReport;
import com.FGInteractive.BatalhaNaval.match.model.Board;
import com.FGInteractive.BatalhaNaval.match.model.Cell;
import java.util.*;

/**
 * Opt-in experiment for square boards. Each power is an independent registered strategy.
 * The semantics and limits must be reviewed against the latest GDD before public release.
 */
public final class SquareAbilityRule implements AbilityRule {
    private interface Behavior {
        AbilityOutcome plan(AbilityContext context, List<Cell> targets);
    }
    private record Entry(AbilitySpec spec, Behavior behavior) {}
    private static final Map<String,Entry> ACTIONS = register();

    private static Map<String,Entry> register() {
        Map<String,Entry> actions = new LinkedHashMap<>();
        add(actions, new AbilitySpec("double-shot","Tiro duplo",1,2,"ENEMY",
            "Dispara em duas casas distintas no mesmo turno."),
            (ctx,t)->AbilityOutcome.of(new AbilityEffect.Attack(t)));

        add(actions, new AbilitySpec("air-strike","Ataque aéreo",1,1,"ENEMY",
            "Ataca as quatro casas de uma área 2x2 a partir do canto superior esquerdo."),
            (ctx,t)-> {
                Cell c=t.get(0);
                List<Cell> area=List.of(c,new Cell(c.row(),c.col()+1),
                    new Cell(c.row()+1,c.col()),new Cell(c.row()+1,c.col()+1));
                if (area.stream().anyMatch(cell->!ctx.mode().geometry().contains(cell)))
                    throw new IllegalArgumentException("Air strike area must fit the board");
                return AbilityOutcome.of(new AbilityEffect.Attack(area));
            });

        add(actions, new AbilitySpec("line-barrage","Rajada em linha",1,2,"ENEMY",
            "Selecione as extremidades de uma linha reta de três casas."),
            (ctx,t)-> {
                Cell a=t.get(0),b=t.get(1);
                if (a.row()==b.row() && Math.abs(a.col()-b.col())==2)
                    return AbilityOutcome.of(new AbilityEffect.Attack(List.of(
                        a,new Cell(a.row(),(a.col()+b.col())/2),b)));
                if (a.col()==b.col() && Math.abs(a.row()-b.row())==2)
                    return AbilityOutcome.of(new AbilityEffect.Attack(List.of(
                        a,new Cell((a.row()+b.row())/2,a.col()),b)));
                throw new IllegalArgumentException("Select a straight line of exactly three cells");
            });

        add(actions, new AbilitySpec("hull-shield","Escudo de casco",1,1,"OWN",
            "Protege um navio intacto contra um acerto por até duas ações inimigas."),
            (ctx,t)-> {
                Cell selected=t.get(0);
                for (ShipPlacement ship:ctx.ownBoard().ships()) {
                    Set<Cell> footprint=new HashSet<>(ctx.mode().placement().footprint(
                        ship,ctx.mode().fleet().require(ship.type()),ctx.mode().geometry()));
                    if (footprint.contains(selected)) {
                        if (footprint.stream().anyMatch(ctx.enemyShots()::contains))
                            throw new IllegalStateException("Only undamaged ships can be shielded");
                        return AbilityOutcome.of(new AbilityEffect.Shield(ship.type()));
                    }
                }
                throw new IllegalArgumentException("Select one of your ships");
            });

        add(actions, new AbilitySpec("last-chance","Última chance",1,0,"NONE",
            "Evita um único disparo fatal durante as próximas três ações inimigas."),
            (ctx,t)->AbilityOutcome.of(new AbilityEffect.LastChance()));

        add(actions, new AbilitySpec("radar","Radar",1,1,"ENEMY",
            "Conta casas inimigas ainda não atingidas no setor 3x3, sem revelar posições."),
            (ctx,t)-> {
                Cell c=t.get(0);
                int hits=(int)ctx.enemyBoard().occupied().stream()
                    .filter(cell->Math.abs(c.row()-cell.row())<=1
                        && Math.abs(c.col()-cell.col())<=1
                        && !ctx.ownShots().contains(cell)).count();
                return AbilityOutcome.of(new AbilityEffect.Scan(
                    new RadarReport(c.row(),c.col(),hits)));
            });

        add(actions, new AbilitySpec("interference","Interferência",1,0,"NONE",
            "Bloqueia o uso de habilidades inimigas durante duas ações do adversário."),
            (ctx,t)->AbilityOutcome.of(new AbilityEffect.Jam()));

        add(actions, new AbilitySpec("retreat","Retirada",1,2,"OWN",
            "Reposiciona um navio intacto em uma casa adjacente."),
            (ctx,t)-> {
                Cell from=t.get(0), to=t.get(1);
                if (Math.abs(from.row()-to.row())+Math.abs(from.col()-to.col())!=1)
                    throw new IllegalArgumentException("Retreat must move one orthogonal cell");
                ShipPlacement chosen=ctx.ownBoard().ships().stream()
                    .filter(ship->ship.row()==from.row()&&ship.col()==from.col())
                    .findFirst().orElseThrow(()->new IllegalArgumentException(
                        "Select the ship's starting cell first"));
                Set<Cell> old=new HashSet<>(ctx.mode().placement().footprint(
                    chosen,ctx.mode().fleet().require(chosen.type()),ctx.mode().geometry()));
                if (old.stream().anyMatch(ctx.enemyShots()::contains))
                    throw new IllegalStateException("Damaged ships cannot retreat");
                ShipPlacement moved=new ShipPlacement(chosen.type(),to.row(),to.col(),chosen.orientation());
                List<ShipPlacement> placements=new ArrayList<>(ctx.ownBoard().ships());
                placements.remove(chosen);
                placements.add(moved);
                Board newBoard=Board.from(ctx.mode(),placements);
                Set<Cell> newCells=new HashSet<>(ctx.mode().placement().footprint(
                    moved,ctx.mode().fleet().require(moved.type()),ctx.mode().geometry()));
                if (newCells.stream().anyMatch(ctx.enemyShots()::contains))
                    throw new IllegalStateException("Cannot retreat onto an already targeted cell");
                return AbilityOutcome.of(new AbilityEffect.Move(newBoard));
            });

        return Map.copyOf(actions);
    }
    private static void add(Map<String,Entry> map, AbilitySpec spec, Behavior behavior) {
        if(map.putIfAbsent(spec.id(),new Entry(spec,behavior))!=null)
            throw new IllegalStateException("Duplicate ability: "+spec.id());
    }

    @Override public String id(){return "square-pilot-v1";}
    @Override public List<AbilitySpec> catalog() {
        // Fixed presentation order, independent of Map implementation iteration order.
        return List.of("double-shot","air-strike","line-barrage","hull-shield",
            "last-chance","radar","interference","retreat")
            .stream().map(id->ACTIONS.get(id).spec()).toList();
    }
    @Override public void validate(GameModeDefinition mode) {
        if (!(mode.geometry() instanceof RectangularGeometry geometry)
            || geometry.rows()!=geometry.columns())
            throw new IllegalArgumentException("Square abilities require a square rectangular board");
    }
    @Override public AbilityOutcome resolve(String id, List<Cell> targets, AbilityContext ctx) {
        validate(ctx.mode());
        Entry action = ACTIONS.get(id);
        if (action==null) throw new IllegalArgumentException("Unknown ability");
        if (targets==null || targets.size()!=action.spec().targetCount())
            throw new IllegalArgumentException("Incorrect number of ability targets");
        if(targets.stream().anyMatch(c->c==null||!ctx.mode().geometry().contains(c))
            || new HashSet<>(targets).size()!=targets.size())
            throw new IllegalArgumentException("Ability targets must be valid, unique cells");
        return action.behavior().plan(ctx,targets);
    }
}
