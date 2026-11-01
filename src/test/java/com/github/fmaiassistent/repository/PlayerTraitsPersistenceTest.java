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

import java.util.List;
import java.util.Map;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {"spring.jpa.show-sql=false", "logging.level.liquibase=warn"})
@Import({SnapshotDatabaseWriter.class, ContractRepository.class})
class PlayerTraitsPersistenceTest {
    @Autowired SnapshotDatabaseWriter writer;
    @Autowired PlayerRepository players;
    @Autowired JdbcTemplate jdbc;

    @Test
    void additiveMigrationKeepsExistingPlayersAndLeavesTheirTraitsUnknown() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:player-traits-upgrade");
             var statement = connection.createStatement();
             var migration = new Liquibase("db/changelog/changes/031-player-traits.xml",
                     new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
            statement.execute("CREATE TABLE players(unique_id BIGINT PRIMARY KEY, name VARCHAR(1024))");
            statement.execute("INSERT INTO players(unique_id,name) VALUES(1,'Existing player')");
            migration.update(new Contexts(), new LabelExpression());
            migration.update(new Contexts(), new LabelExpression());
            try (var rows = statement.executeQuery("SELECT name,player_traits,player_traits_raw_hex FROM players WHERE unique_id=1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("Existing player");
                assertThat(rows.getString(2)).isNull();
                assertThat(rows.getString(3)).isNull();
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void snapshotWriterPreservesTraitsRawBitsAndDistinctEmptyAndLegacyStates() {
        writer.savePlayers(new PlayerExporter.ExportResult("", List.of(
                Map.of("unique_id", 1L, "name", "Trait player", "player_traits", "Moves Into Channels",
                        "player_traits_raw_hex", "0000000000000010"),
                Map.of("unique_id", 2L, "name", "No traits", "player_traits", "",
                        "player_traits_raw_hex", "0000000000000000"),
                Map.of("unique_id", 3L, "name", "Legacy"))), Map.of());
        var loaded = players.findAll().stream().filter(player -> player.getUniqueId() == 1L).findFirst().orElseThrow();
        assertThat(loaded.getPlayerTraits()).isEqualTo("Moves Into Channels");
        assertThat(loaded.getPlayerTraitsRawHex()).isEqualTo("0000000000000010");
        assertThat(jdbc.queryForObject("SELECT player_traits FROM players WHERE unique_id=2", String.class)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT player_traits FROM players WHERE unique_id=3", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT player_traits_raw_hex FROM players WHERE unique_id=3", String.class)).isNull();
    }
}
