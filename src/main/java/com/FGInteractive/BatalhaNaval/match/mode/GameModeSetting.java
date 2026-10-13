package com.FGInteractive.BatalhaNaval.match.mode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Database-owned availability metadata, never a persistent copy of rules. */
@Entity
@Table(name = "game_mode_settings")
public class GameModeSetting {
    @Id
    @Column(name = "mode_id", nullable = false, length = 32)
    private String modeId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected GameModeSetting() {}

    public GameModeSetting(String modeId, boolean enabled, Instant updatedAt, Long updatedBy) {
        this.modeId = modeId;
        this.enabled = enabled;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public String getModeId() { return modeId; }
    public boolean isEnabled() { return enabled; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getUpdatedBy() { return updatedBy; }
}
