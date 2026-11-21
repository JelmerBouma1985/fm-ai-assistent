package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.exporter.PlayerExporter;
import com.github.fmaiassistent.service.SnapshotDatabaseWriter;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {"spring.jpa.show-sql=false", "logging.level.liquibase=warn"})
@Import({SnapshotDatabaseWriter.class, ContractRepository.class})
class PlayerFitnessPersistenceTest {
    @Autowired SnapshotDatabaseWriter writer;
    @Autowired PlayerRepository players;
    @Autowired JdbcTemplate jdbc;

    @Test
    void additiveUpgradeKeepsExistingPlayersWithUnknownFitness() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:player-fitness-upgrade");
             var statement = connection.createStatement();
             var migration = new Liquibase("db/changelog/changes/034-player-fitness.xml",
                     new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
            statement.execute("CREATE TABLE players(unique_id BIGINT PRIMARY KEY, name VARCHAR(1024))");
            statement.execute("INSERT INTO players VALUES(1,'Existing player')");
            migration.update(new Contexts(), new LabelExpression());
            migration.update(new Contexts(), new LabelExpression());
            try (var rows = statement.executeQuery("SELECT * FROM players WHERE unique_id=1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("name")).isEqualTo("Existing player");
                for (String field : List.of("condition", "sharpness", "fatigue", "match_load", "training_load",
                        "injury_susceptibility", "overall_risk")) assertThat(rows.getObject(field)).isNull();
            }
        }
    }

    @Test
    void snapshotWriterRoundTripsFitnessAndPreservesUnknownLegacyValues() {
        writer.savePlayers(new PlayerExporter.ExportResult("", List.of(
                Map.of("unique_id", 1L, "name", "Fitness player", "condition", 9587, "sharpness", 10000,
                        "fatigue", -123, "match_load", "Light", "training_load", "Medium",
                        "injury_susceptibility", "Above Average", "overall_risk", "Increased"),
                Map.of("unique_id", 2L, "name", "Legacy"))), Map.of());
        var player = players.findAll().stream().filter(p -> p.getUniqueId() == 1L).findFirst().orElseThrow();
        assertThat(player.getCondition()).isEqualTo(9587);
        assertThat(player.getSharpness()).isEqualTo(10000);
        assertThat(player.getFatigue()).isEqualTo(-123);
        assertThat(player.getMatchLoad()).isEqualTo("Light");
        assertThat(player.getTrainingLoad()).isEqualTo("Medium");
        assertThat(player.getInjurySusceptibility()).isEqualTo("Above Average");
        assertThat(player.getOverallRisk()).isEqualTo("Increased");
        for (String field : List.of("condition", "sharpness", "fatigue", "match_load", "training_load",
                "injury_susceptibility", "overall_risk"))
            assertThat(jdbc.queryForObject("SELECT " + field + " FROM players WHERE unique_id=2", Object.class)).isNull();
    }
}
