package com.github.fmaiassistent.repository;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.Scope;
import liquibase.changelog.FastCheckService;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaffContractOwnershipMigrationTest {
    @Test void expandsOwnershipPreservesPlayerRowsAndRefusesDestructiveRollback() throws Exception {
        String change="db/changelog/changes/017-staff-contract-ownership.xml";
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:staff-contract-migration;DB_CLOSE_DELAY=-1");var sql=connection.createStatement()) {
            sql.execute("CREATE TABLE players(id BIGINT PRIMARY KEY)");sql.execute("CREATE TABLE staff(id BIGINT PRIMARY KEY)");
            sql.execute("CREATE TABLE player_contracts(id BIGINT PRIMARY KEY,player_id BIGINT NOT NULL,slot INT,core_hex VARCHAR(496),CONSTRAINT fk_contract_player FOREIGN KEY(player_id) REFERENCES players(id) ON DELETE CASCADE,CONSTRAINT uq_player_contract_slot UNIQUE(player_id,slot))");
            sql.execute("INSERT INTO players VALUES(1)");sql.execute("INSERT INTO staff VALUES(1)");
            sql.execute("INSERT INTO player_contracts VALUES(1,1,-1,'00112233')");
            try(var l=new Liquibase(change,new ClassLoaderResourceAccessor(),new JdbcConnection(connection))) {
                l.update(new Contexts(),new LabelExpression());l.update(new Contexts(),new LabelExpression());
                try(var rs=sql.executeQuery("SELECT player_id,staff_id,core_hex FROM player_contracts WHERE id=1")){assertThat(rs.next()).isTrue();assertThat(rs.getLong(1)).isEqualTo(1);assertThat(rs.getObject(2)).isNull();assertThat(rs.getString(3)).isEqualTo("00112233");}
                sql.execute("INSERT INTO player_contracts(id,staff_id,slot,core_hex) VALUES(2,1,-1,'445566')");
                connection.commit(); // Exercise rollback against persisted rows, as in the application.
                assertThatThrownBy(() -> sql.execute("INSERT INTO player_contracts(id,player_id,staff_id,slot) VALUES(3,1,1,0)")).isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(() -> sql.execute("INSERT INTO player_contracts(id,slot) VALUES(3,0)")).isInstanceOf(java.sql.SQLException.class);
                try(var rs=sql.executeQuery("SELECT COUNT(*) FROM player_contracts WHERE player_id IS NULL")){assertThat(rs.next()).isTrue();assertThat(rs.getInt(1)).isEqualTo(1);}
                assertThatThrownBy(() -> l.rollback(1,new Contexts(),new LabelExpression())).isInstanceOf(liquibase.exception.LiquibaseException.class);
                try(var rs=sql.executeQuery("SELECT core_hex FROM player_contracts WHERE staff_id=1")){assertThat(rs.next()).isTrue();assertThat(rs.getString(1)).isEqualTo("445566");}
                // Only a fresh empty-child fixture permits rollback; production rows are never discarded by rollback.
                sql.execute("DELETE FROM player_contracts WHERE staff_id=1");
                connection.commit();
                l.rollback(1,new Contexts(),new LabelExpression());
                try(var columns=connection.getMetaData().getColumns(null,null,"PLAYER_CONTRACTS","STAFF_ID")){assertThat(columns.next()).isFalse();}
                try(var rs=sql.executeQuery("SELECT core_hex FROM player_contracts WHERE id=1")){assertThat(rs.next()).isTrue();assertThat(rs.getString(1)).isEqualTo("00112233");}
            }
        }
        Scope.getCurrentScope().getSingleton(FastCheckService.class).clearCache();
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:staff-contract-migration;DB_CLOSE_DELAY=-1");var l=new Liquibase(change,new ClassLoaderResourceAccessor(),new JdbcConnection(connection))) {
            l.update(new Contexts(),new LabelExpression());
            try(var rs=connection.createStatement().executeQuery("SELECT core_hex,staff_id FROM player_contracts WHERE id=1")){assertThat(rs.next()).isTrue();assertThat(rs.getString(1)).isEqualTo("00112233");assertThat(rs.getObject(2)).isNull();}
        }
    }
}
