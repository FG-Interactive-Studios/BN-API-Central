package com.FGInteractive.BatalhaNaval.match.mode;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Register new mode definitions as Spring beans; no central switch/case. */
@Component
public class GameModeRegistry {
    private final Map<String,GameModeDefinition> byId;

    public GameModeRegistry(List<GameModeDefinition> definitions) {
        Map<String,GameModeDefinition> map = new LinkedHashMap<>();
        for (GameModeDefinition mode : definitions) {
            if (map.putIfAbsent(mode.id(), mode) != null)
                throw new IllegalStateException("Duplicate game mode id: " + mode.id());
            for (RuleModule module : mode.rules().values()) module.validate(mode);
        }
        if (!map.containsKey("classic")) throw new IllegalStateException("Classic mode missing");
        byId = Map.copyOf(map);
    }

    public GameModeDefinition get(String requested) {
        String id = requested == null ? "classic" : requested;
        GameModeDefinition mode = byId.get(id);
        if (mode == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Unknown game mode");
        return mode;
    }

    public List<ModeSummary> list() {
        return byId.values().stream().map(GameModeDefinition::summary)
            .sorted(Comparator.comparing(ModeSummary::id)).toList();
    }
}
