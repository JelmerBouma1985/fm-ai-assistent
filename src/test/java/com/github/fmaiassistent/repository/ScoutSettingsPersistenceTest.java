package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.domain.enums.ScoutingMode;
import com.github.fmaiassistent.service.ScoutSettingsService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {"spring.jpa.show-sql=false", "logging.level.liquibase=warn"})
@Import({ScoutSettingsService.class, DatabaseService.class})
class ScoutSettingsPersistenceTest {
    @Autowired ScoutSettingsService settings;
    @Autowired ScoutSettingsRepository repository;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired DatabaseService database;

    @Test
    void defaultAndBothChoicesSurviveDatabaseRereads() {
        assertThat(settings.mode()).isEqualTo(ScoutingMode.BEST_CLUB_SCOUT);
        assertThat(jdbc.queryForObject("SELECT scouting_mode FROM scout_settings WHERE id=1", String.class))
                .isEqualTo("BEST_CLUB_SCOUT");
        for (ScoutingMode mode : new ScoutingMode[]{ScoutingMode.PERFECT_SCOUT, ScoutingMode.BEST_CLUB_SCOUT}) {
            settings.saveMode(mode);
            entityManager.flush();
            entityManager.clear();
            assertThat(new ScoutSettingsService(repository).mode()).isEqualTo(mode);
            assertThat(jdbc.queryForObject("SELECT scouting_mode FROM scout_settings WHERE id=1", String.class))
                    .isEqualTo(mode.name());
        }
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void absentRowAndNullChoiceDefaultToClubScout() {
        repository.deleteAll();
        entityManager.flush();
        entityManager.clear();
        assertThat(settings.mode()).isEqualTo(ScoutingMode.BEST_CLUB_SCOUT);
        settings.saveMode(null);
        entityManager.flush();
        entityManager.clear();
        assertThat(settings.mode()).isEqualTo(ScoutingMode.BEST_CLUB_SCOUT);
    }

    @Test
    void snapshotReplacementDoesNotEraseTheSavedScoutChoice() {
        settings.saveMode(ScoutingMode.PERFECT_SCOUT);
        settings.saveWarnForLargeBatch(false);
        entityManager.flush();
        database.clearAllTables();
        entityManager.clear();
        assertThat(new ScoutSettingsService(repository).mode()).isEqualTo(ScoutingMode.PERFECT_SCOUT);
        assertThat(new ScoutSettingsService(repository).warnForLargeBatch()).isFalse();
    }

    @Test
    void warningDefaultsToEnabledForExistingNullAndMissingRows() {
        assertThat(settings.warnForLargeBatch()).isTrue();
        assertThat(jdbc.queryForObject("SELECT warn_for_large_batch FROM scout_settings WHERE id=1", Boolean.class)).isTrue();
        jdbc.update("UPDATE scout_settings SET warn_for_large_batch=NULL WHERE id=1");
        entityManager.clear();
        assertThat(settings.warnForLargeBatch()).isTrue();
        repository.deleteAll();
        entityManager.flush();
        entityManager.clear();
        assertThat(settings.warnForLargeBatch()).isTrue();
    }

    @Test
    void warningAndScoutModeUpdatesPreserveEachOtherAcrossRereads() {
        settings.saveWarnForLargeBatch(false);
        entityManager.flush();
        entityManager.clear();
        assertThat(new ScoutSettingsService(repository).warnForLargeBatch()).isFalse();
        settings.saveMode(ScoutingMode.PERFECT_SCOUT);
        entityManager.flush();
        entityManager.clear();
        assertThat(settings.warnForLargeBatch()).isFalse();
        assertThat(settings.mode()).isEqualTo(ScoutingMode.PERFECT_SCOUT);
        settings.saveWarnForLargeBatch(true);
        entityManager.flush();
        entityManager.clear();
        assertThat(settings.warnForLargeBatch()).isTrue();
        assertThat(settings.mode()).isEqualTo(ScoutingMode.PERFECT_SCOUT);
    }
}
