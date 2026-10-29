package com.github.fmaiassistent.repository;
import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;
class ClubFinancialCompetitionMigrationTest {
    @Test void legacyRowsSurviveAndPopulatedRollbackFailsWithoutDataLoss()throws Exception {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:club-financial-comp-migration");var s=c.createStatement()) {
            s.execute("CREATE TABLE club_records(unique_id BIGINT PRIMARY KEY,kind VARCHAR(32),name VARCHAR(100),financial_ledger_hex VARCHAR(2320),financial_ledger_clock INT)");
            s.execute("INSERT INTO club_records VALUES(1036,'CLUB','sc Heerenveen',REPEAT('00',1160),133438093),(7,'CLUB','Legacy',NULL,NULL)");
            try(var m=new Liquibase("db/changelog/changes/025-club-financial-competitions.xml",new ClassLoaderResourceAccessor(),new JdbcConnection(c))) {
                m.update(new Contexts(),new LabelExpression());
                try(var r=s.executeQuery("SELECT name,financial_competitions_hex FROM club_records WHERE unique_id=1036")){r.next();assertThat(r.getString(1)).isEqualTo("sc Heerenveen");assertThat(r.getString(2)).isNull();}
                assertThatThrownBy(()->s.execute("UPDATE club_records SET financial_competitions_hex='' WHERE unique_id=1036")).isInstanceOf(SQLException.class);
                assertThatThrownBy(()->s.execute("UPDATE club_records SET financial_competitions_hex=REPEAT('00',40) WHERE unique_id=7")).isInstanceOf(SQLException.class);
                s.execute("UPDATE club_records SET financial_competitions_hex=REPEAT('00',40) WHERE unique_id=1036");c.commit();
                assertThatThrownBy(()->m.rollback(1,new Contexts(),new LabelExpression())).isInstanceOf(Exception.class);
                try(var r=s.executeQuery("SELECT LENGTH(financial_competitions_hex) FROM club_records WHERE unique_id=1036")){r.next();assertThat(r.getInt(1)).isEqualTo(80);}
                s.execute("UPDATE club_records SET financial_competitions_hex=NULL");c.commit();m.rollback(1,new Contexts(),new LabelExpression());
                try(var r=s.executeQuery("SELECT name FROM club_records WHERE unique_id=1036")){r.next();assertThat(r.getString(1)).isEqualTo("sc Heerenveen");}
            }
        }
    }
}
