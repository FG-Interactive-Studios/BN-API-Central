package com.FGInteractive.BatalhaNaval.match.mode;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Mode catalog, guarded by the default JWT security policy. */
@RestController
@RequestMapping("/api/game-modes")
public class GameModeController {
    private final GameModeRegistry modes;
    public GameModeController(GameModeRegistry modes) { this.modes = modes; }
    @GetMapping public List<ModeSummary> list() { return modes.list(); }
}
