package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.domain.entity.ClubEntity;
import com.github.fmaiassistent.domain.entity.CompetitionEntity;
import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.exporter.PlayerExporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.show-sql=false",
        "logging.level.liquibase=warn"
})
class CatalogSpecificationsIntegrationTest {
    @Autowired private PlayerRepository players;
    @Autowired private ClubRepository clubs;
    @Autowired private CompetitionRepository competitions;

    @Test
    void filtersPlayersInTheDatabaseAcrossCatalogRelationshipsAndNumericFields() {
        CompetitionEntity eredivisie = competition("Eredivisie", "Netherlands", "Male", 1L);
        competitions.save(eredivisie);
        ClubEntity heerenveen = club("sc Heerenveen", "Eredivisie", "Netherlands", "Male", 2L);
        heerenveen.setCompetitionEntity(eredivisie);
        clubs.save(heerenveen);
        players.saveAll(List.of(
                player("100% Talent", 101L, 23, 151, 160, 18, 20_000, heerenveen),
                player("Older Player", 102L, 31, 145, 145, 12, 60_000, heerenveen)));

        assertThat(players.findAll()).extracting(PlayerEntity::getUniqueId)
                .containsExactlyInAnyOrder(101L, 102L);
        PlayerEntity persistedTalent = players.findFirstByUniqueId(101L).orElseThrow();
        assertThat(List.of(
                persistedTalent.getAge(), persistedTalent.getCa(), persistedTalent.getPa(),
                persistedTalent.getDefenderCentral(), persistedTalent.getSalaryWeeklyRaw()))
                .containsExactly(23, 151, 160, 18, 20_000);
        assertThat(players.findAll(CatalogSpecifications.players(new PlayerFilterCriteria(
                "%", null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, Map.of(), Map.of()))))
                .extracting(PlayerEntity::getUniqueId).containsExactly(101L);
        assertThat(players.findAll(CatalogSpecifications.players(new PlayerFilterCriteria(
                null, "Male", "Netherlands", "Eredivisie", "sc Heerenveen",
                null, null, null, null, "Dutch",
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, Map.of(), Map.of()))))
                .extracting(PlayerEntity::getUniqueId).containsExactlyInAnyOrder(101L, 102L);
        assertThat(players.findAll(CatalogSpecifications.players(new PlayerFilterCriteria(
                null, null, null, null, null,
                18, 24, null, null, null,
                null, null, null, null, null, null,
                150, null, 155, null, null, null,
                null, null, 30_000L, Map.of("DEFENDER_CENTRAL", 15), Map.of()))))
                .extracting(PlayerEntity::getUniqueId).containsExactly(101L);

        PlayerFilterCriteria filter = new PlayerFilterCriteria(
                "%", "Male", "Netherlands", "Eredivisie", "sc Heerenveen",
                18, 24, null, null, "Dutch",
                null, null, null, null, null, null,
                150, null, 155, null, null, null,
                null, null, 30_000L,
                Map.of("DEFENDER_CENTRAL", 15), Map.of());

        assertThat(players.findAll(CatalogSpecifications.players(filter)))
                .extracting(PlayerEntity::getUniqueId)
                .containsExactly(101L);
    }

    private static CompetitionEntity competition(
            String name, String nation, String gender, long address) {
        return CompetitionEntity.fromExportRow(Map.of(
                "sourceAddress", address,
                "name", name,
                "nation", nation,
                "reputation", 160,
                "gender", gender));
    }

    private static ClubEntity club(
            String name, String competition, String nation, String gender, long address) {
        return ClubEntity.fromExportRow(Map.of(
                "sourceAddress", address,
                "name", name,
                "competition", competition,
                "nation", nation,
                "gender", gender,
                "reputation", 7_000));
    }

    private static PlayerEntity player(
            String name,
            long uniqueId,
            int age,
            int ca,
            int pa,
            int defenderCentral,
            int salary,
            ClubEntity club) {
        Map<String, Object> row = new HashMap<>();
        PlayerExporter.FIELD_NAMES.forEach(field -> row.put(field, null));
        row.put("name", name);
        row.put("unique_id", uniqueId);
        row.put("age", age);
        row.put("ca", ca);
        row.put("pa", pa);
        row.put("DefenderCentral", defenderCentral);
        row.put("salary_weekly_raw", salary);
        row.put("nationality", "Dutch");
        row.put("gender", "Male");
        row.put("club", club.getName());
        row.put("playing_club", club.getName());
        PlayerEntity player = PlayerEntity.fromExportRow(row);
        player.setClubEntity(club);
        player.setPlayingClubEntity(club);
        return player;
    }
}
