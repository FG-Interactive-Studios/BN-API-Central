package com.FGInteractive.BatalhaNaval.match.mode;

import com.FGInteractive.BatalhaNaval.user.repository.UserRepository;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Administrative availability for registered mode definitions.
 *
 * One shared monitor serializes enabling/disabling with NEW lobby allocation
 * (single-instance academic server). Existing lobbies hold immutable mode
 * definitions and are never rewritten when a mode is disabled.
 */
@Service
public class GameModeAvailabilityService {
    private final Object mutex = new Object();
    private final GameModeRegistry modes;
    private final GameModeSettingRepository settings;
    private final UserRepository users;

    public GameModeAvailabilityService(GameModeRegistry modes, GameModeSettingRepository settings,
                                       UserRepository users) {
        this.modes = modes;
        this.settings = settings;
        this.users = users;
    }

    /** Public authenticated catalog: players only see enabled mode definitions. */
    public List<ModeSummary> enabledCatalog() {
        synchronized (mutex) {
            Map<String,GameModeSetting> known = knownSettings();
            return modes.list().stream()
                .filter(mode -> enabled(known, mode.id()))
                .toList();
        }
    }

    /**
     * Hold the same monitor from availability check through room allocation.
     * If an admin disables a mode concurrently, the ordering is deterministic.
     */
    public <T> T withEnabledMode(String id, Function<GameModeDefinition,T> allocate) {
        synchronized (mutex) {
            GameModeDefinition definition = modes.get(id);
            if (!settings.findById(definition.id()).map(GameModeSetting::isEnabled).orElse(false)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This game mode is disabled for new lobbies");
            }
            return allocate.apply(definition);
        }
    }

    public List<AdminGameModeResponse> adminCatalog(long actorId) {
        synchronized (mutex) {
            requireAdmin(actorId);
            Map<String,GameModeSetting> known = knownSettings();
            return modes.list().stream()
                .map(mode -> response(mode, known.get(mode.id())))
                .toList();
        }
    }

    public AdminGameModeResponse change(long actorId, String id, Boolean value) {
        synchronized (mutex) {
            requireAdmin(actorId);
            if (value == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "enabled must be a boolean");
            }

            GameModeDefinition mode;
            try {
                mode = modes.get(id);
            } catch (ResponseStatusException exception) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown game mode");
            }
            GameModeSetting current = settings.findById(mode.id()).orElse(null);
            if (current != null && current.isEnabled() == value) {
                return response(mode.summary(), current);
            }
            if (!value) {
                Map<String,GameModeSetting> known = knownSettings();
                long active = modes.list().stream()
                    .filter(candidate -> enabled(known, candidate.id())).count();
                if (active <= 1 && enabled(known, mode.id())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "At least one registered game mode must remain enabled");
                }
            }

            GameModeSetting saved = settings.saveAndFlush(
                new GameModeSetting(mode.id(), value, Instant.now(), actorId));
            return response(mode.summary(), saved);
        }
    }

    private void requireAdmin(long actorId) {
        // Read from the database on every action, not a possibly stale JWT claim.
        if (!users.existsByIdAndAdminTrue(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Administrator access required");
        }
    }

    private Map<String,GameModeSetting> knownSettings() {
        Map<String,GameModeSetting> result = new HashMap<>();
        for (GameModeSetting setting : settings.findAll()) {
            result.put(setting.getModeId(), setting);
        }
        return result;
    }

    private static boolean enabled(Map<String,GameModeSetting> settings, String modeId) {
        GameModeSetting status = settings.get(modeId);
        return status != null && status.isEnabled();
    }

    private static AdminGameModeResponse response(ModeSummary mode, GameModeSetting setting) {
        return new AdminGameModeResponse(mode,
            setting != null && setting.isEnabled(),
            setting == null ? null : setting.getUpdatedAt(),
            setting == null ? null : setting.getUpdatedBy());
    }
}
