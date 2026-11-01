package com.github.fmaiassistent.domain.entity;

import com.github.fmaiassistent.domain.enums.ScoutingMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "scout_settings")
public class ScoutSettingsEntity {
    @Id
    private Integer id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scouting_mode", nullable = false, length = 32)
    private ScoutingMode scoutingMode;

    @Column(name = "warn_for_large_batch")
    private Boolean warnForLargeBatch;

    protected ScoutSettingsEntity() {
    }

    public ScoutSettingsEntity(ScoutingMode mode) {
        this(mode, true);
    }

    public ScoutSettingsEntity(ScoutingMode mode, boolean warnForLargeBatch) {
        this.id = 1;
        this.scoutingMode = mode == null ? ScoutingMode.BEST_CLUB_SCOUT : mode;
        this.warnForLargeBatch = warnForLargeBatch;
    }

    public Integer getId() {
        return id;
    }

    public ScoutingMode getScoutingMode() {
        return scoutingMode;
    }

    public boolean isWarnForLargeBatch() {
        return !Boolean.FALSE.equals(warnForLargeBatch);
    }
}
