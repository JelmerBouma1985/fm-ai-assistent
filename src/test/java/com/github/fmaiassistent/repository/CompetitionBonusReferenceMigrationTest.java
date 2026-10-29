package com.github.fmaiassistent.repository;

import liquibase.*;
import liquibase.changelog.FastCheckService;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;

class CompetitionBonusReferenceMigrationTest {
    @Test void nullableExpansionPreservesLegacyRowsAndBlocksDestructiveRollback()throws Exception {
        String url="jdbc:h2:mem:competition-bonus-ref-migration;DB_CLOSE_DELAY=-1";
        String change="db/changelog/changes/029-contract-competition-references.xml";
        try(var c=DriverManager.getConnection(url);var sql=c.createStatement();
            var migration=new Liquibase(change,new ClassLoaderResourceAccessor(),new JdbcConnection(c))) {
            sql.execute("CREATE TABLE contract_terms (term_group VARCHAR(32),raw_hex VARCHAR(64))");
            sql.execute("INSERT INTO contract_terms VALUES ('FULL_80','00112233')");
            migration.update(new Contexts(),new LabelExpression());
            migration.update(new Contexts(),new LabelExpression());
            try(var r=sql.executeQuery("SELECT raw_hex,subject_competition_uid,condition_competition_uid FROM contract_terms")) {
                assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("00112233");
                assertThat(r.getObject(2)).isNull();assertThat(r.getObject(3)).isNull();
            }
            migration.rollback(1,new Contexts(),new LabelExpression());
        }
        Scope.getCurrentScope().getSingleton(FastCheckService.class).clearCache();
        try(var c=DriverManager.getConnection(url);var sql=c.createStatement();
            var migration=new Liquibase(change,new ClassLoaderResourceAccessor(),new JdbcConnection(c))) {
            migration.update(new Contexts(),new LabelExpression());
            sql.execute("UPDATE contract_terms SET subject_competition_uid=32,condition_competition_uid=1301394");
            c.commit(); // Liquibase rolls back pending JDBC work before executing its rollback changes.
            assertThatThrownBy(()->migration.rollback(1,new Contexts(),new LabelExpression())).isInstanceOf(liquibase.exception.LiquibaseException.class);
            try(var r=sql.executeQuery("SELECT raw_hex,subject_competition_uid,condition_competition_uid FROM contract_terms")) {
                assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("00112233");
                assertThat(r.getLong(2)).isEqualTo(32);assertThat(r.getLong(3)).isEqualTo(1301394);
            }
            assertThatThrownBy(()->sql.execute("UPDATE contract_terms SET subject_competition_uid=0")).isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(()->sql.execute("UPDATE contract_terms SET term_group='FULL_68'")).isInstanceOf(java.sql.SQLException.class);
        }
    }
}
