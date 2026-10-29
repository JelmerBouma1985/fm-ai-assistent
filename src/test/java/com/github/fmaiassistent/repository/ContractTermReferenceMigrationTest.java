package com.github.fmaiassistent.repository;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.Scope;
import liquibase.changelog.FastCheckService;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class ContractTermReferenceMigrationTest {
    @ParameterizedTest
    @CsvSource({
            "contract-reference-migration,contract_terms,raw_hex,reference_kind,reference_uid,015-contract-term-references.xml",
            "contract-owner-migration,player_contracts,core_hex,team_owner_kind,team_owner_uid,016-contract-team-owners.xml"
    })
    void nullableExpansionPreservesOldRowsAndSupportsRetryRollbackAndReapply(
            String db, String table, String rawColumn, String kindColumn, String uidColumn, String file) throws Exception {
        String url = "jdbc:h2:mem:"+db+";DB_CLOSE_DELAY=-1";
        String changelog="db/changelog/changes/"+file;
        try (var connection = DriverManager.getConnection(url);
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE "+table+"(id BIGINT,"+rawColumn+" VARCHAR(496))");
            sql.execute("INSERT INTO "+table+" VALUES(1,'00112233')");
            try (var liquibase = new Liquibase(changelog,
                    new ClassLoaderResourceAccessor(),new JdbcConnection(connection))) {
                liquibase.update(new Contexts(),new LabelExpression());
                liquibase.update(new Contexts(),new LabelExpression()); // Already-applied update is idempotent.
                try (var result = sql.executeQuery("SELECT "+rawColumn+","+kindColumn+","+uidColumn+" FROM "+table)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("00112233");
                    assertThat(result.getObject(2)).isNull();
                    assertThat(result.getObject(3)).isNull();
                }
                liquibase.rollback(1,new Contexts(),new LabelExpression());
                try (var result = sql.executeQuery("SELECT "+rawColumn+" FROM "+table)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("00112233");
                }
                try (var columns = connection.getMetaData().getColumns(null,null,table.toUpperCase(java.util.Locale.ROOT),uidColumn.toUpperCase(java.util.Locale.ROOT))) {
                    assertThat(columns.next()).isFalse();
                }
            }
        }
        // Reapply through a fresh database/Liquibase session, as an application restart does.
        // Liquibase5 caches its up-to-date answer process-wide, even across rollback/connections.
        Scope.getCurrentScope().getSingleton(FastCheckService.class).clearCache();
        try (var connection = DriverManager.getConnection(url);
             var sql = connection.createStatement();
             var liquibase = new Liquibase(changelog,
                     new ClassLoaderResourceAccessor(),new JdbcConnection(connection))) {
            liquibase.update(new Contexts(),new LabelExpression());
            try (var result = sql.executeQuery("SELECT "+rawColumn+","+uidColumn+" FROM "+table)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("00112233");
                assertThat(result.getObject(2)).isNull();
            }
        }
    }
}
