package com.github.fmaiassistent.service;

import com.github.fmaiassistent.domain.entity.ScoutSettingsEntity;
import com.github.fmaiassistent.domain.enums.ScoutingMode;
import com.github.fmaiassistent.repository.ScoutSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScoutSettingsService {
    private final ScoutSettingsRepository repository;

    public ScoutSettingsService(ScoutSettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ScoutingMode mode() {
        return repository.findById(1).map(ScoutSettingsEntity::getScoutingMode)
                .orElse(ScoutingMode.BEST_CLUB_SCOUT);
    }

    @Transactional
    public void saveMode(ScoutingMode mode) {
        boolean warning = repository.findById(1).map(ScoutSettingsEntity::isWarnForLargeBatch).orElse(true);
        repository.save(new ScoutSettingsEntity(mode, warning));
    }

    @Transactional(readOnly = true)
    public boolean warnForLargeBatch() {
        return repository.findById(1).map(ScoutSettingsEntity::isWarnForLargeBatch).orElse(true);
    }

    @Transactional
    public void saveWarnForLargeBatch(boolean warning) {
        ScoutingMode mode = repository.findById(1).map(ScoutSettingsEntity::getScoutingMode)
                .orElse(ScoutingMode.BEST_CLUB_SCOUT);
        repository.save(new ScoutSettingsEntity(mode, warning));
    }
}
