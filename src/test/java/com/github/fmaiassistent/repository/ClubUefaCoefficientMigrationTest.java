package com.github.fmaiassistent.repository;

import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

class ClubUefaCoefficientMigrationTest {
    @Test void oldRowsRemainReadableAndPopulatedProfilesCannotBeSilentlyRolledBack()throws Exception {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:club-uefa-migration");var s=c.createStatement()) {
            s.execute("CREATE TABLE club_records(unique_id BIGINT PRIMARY KEY,kind VARCHAR(32),name VARCHAR(100))");
            s.execute("INSERT INTO club_records VALUES(1036,'CLUB','sc Heerenveen'),(7,'LEAGUE_BODY_CLUB','Association')");
            try(var migration=new Liquibase("db/changelog/changes/021-club-uefa-coefficients.xml",new ClassLoaderResourceAccessor(),new JdbcConnection(c))) {
                migration.update(new Contexts(),new LabelExpression());
                try(var r=s.executeQuery("SELECT name,uefa_coefficient_hex,uefa_rule_version FROM club_records WHERE unique_id=1036")) {
                    assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("sc Heerenveen");assertThat(r.getString(2)).isNull();assertThat(r.getObject(3)).isNull();
                }
                String raw="00".repeat(76);
                assertThatThrownBy(()->s.execute("UPDATE club_records SET uefa_coefficient_hex='"+raw+"' WHERE unique_id=1036")).isInstanceOf(SQLException.class);
                assertThatThrownBy(()->s.execute("UPDATE club_records SET uefa_coefficient_hex='"+raw+"',uefa_rule_version=65536 WHERE unique_id=1036")).isInstanceOf(SQLException.class);
                assertThatThrownBy(()->s.execute("UPDATE club_records SET uefa_coefficient_hex='"+raw+"',uefa_rule_version=1639 WHERE unique_id=7")).isInstanceOf(SQLException.class);
                s.execute("UPDATE club_records SET uefa_coefficient_hex='"+raw+"',uefa_rule_version=1639 WHERE unique_id=1036");c.commit();
                assertThatThrownBy(()->migration.rollback(1,new Contexts(),new LabelExpression())).isInstanceOf(Exception.class);
                try(var r=s.executeQuery("SELECT uefa_coefficient_hex FROM club_records WHERE unique_id=1036")){r.next();assertThat(r.getString(1)).isEqualTo(raw);}
                s.execute("UPDATE club_records SET uefa_coefficient_hex=NULL,uefa_rule_version=NULL");c.commit();
                migration.rollback(1,new Contexts(),new LabelExpression());
                try(var r=c.getMetaData().getColumns(null,null,"CLUB_RECORDS","UEFA_COEFFICIENT_HEX")){assertThat(r.next()).isFalse();}
                try(var r=s.executeQuery("SELECT name FROM club_records WHERE unique_id=1036")){r.next();assertThat(r.getString(1)).isEqualTo("sc Heerenveen");}
            }
        }
    }
}
