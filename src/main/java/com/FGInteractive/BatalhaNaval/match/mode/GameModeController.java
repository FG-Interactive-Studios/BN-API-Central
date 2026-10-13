package com.FGInteractive.BatalhaNaval.match.mode;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated player catalog: disabled modes are not advertised. */
@RestController
@RequestMapping("/api/game-modes")
public class GameModeController {
    private final GameModeAvailabilityService availability;
    public GameModeController(GameModeAvailabilityService availability) {
        this.availability = availability;
    }
    @GetMapping
    public List<ModeSummary> list() { return availability.enabledCatalog(); }
}
