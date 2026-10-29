package com.github.fmaiassistent.repository;
import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;
class ClubFinancialLedgerMigrationTest {
    @Test void legacyRowsSurviveExpansionAndRollbackCannotDiscardCapturedProfiles()throws Exception {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:club-ledger-migration");var s=c.createStatement()) {
            s.execute("CREATE TABLE club_records(unique_id BIGINT PRIMARY KEY,kind VARCHAR(32),name VARCHAR(100))");
            s.execute("INSERT INTO club_records VALUES(1036,'CLUB','sc Heerenveen'),(7,'LEAGUE_BODY_CLUB','Association')");
            try(var m=new Liquibase("db/changelog/changes/024-club-financial-ledger.xml",new ClassLoaderResourceAccessor(),new JdbcConnection(c))) {
                m.update(new Contexts(),new LabelExpression());
                try(var r=s.executeQuery("SELECT name,financial_ledger_hex,financial_ledger_clock FROM club_records WHERE unique_id=1036")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("sc Heerenveen");assertThat(r.getString(2)).isNull();assertThat(r.getObject(3)).isNull();}
                assertThatThrownBy(()->s.execute("UPDATE club_records SET financial_ledger_hex=REPEAT('00',1160) WHERE unique_id=1036")).isInstanceOf(SQLException.class);
                assertThatThrownBy(()->s.execute("UPDATE club_records SET financial_ledger_hex='',financial_ledger_clock=133438093 WHERE unique_id=1036")).isInstanceOf(SQLException.class);
                assertThatThrownBy(()->s.execute("UPDATE club_records SET financial_ledger_hex=REPEAT('00',1160),financial_ledger_clock=133438093 WHERE unique_id=7")).isInstanceOf(SQLException.class);
                s.execute("UPDATE club_records SET financial_ledger_hex=REPEAT('00',1160),financial_ledger_clock=133438093 WHERE unique_id=1036");c.commit();
                assertThatThrownBy(()->m.rollback(1,new Contexts(),new LabelExpression())).isInstanceOf(Exception.class);
                try(var r=s.executeQuery("SELECT LENGTH(financial_ledger_hex) FROM club_records WHERE unique_id=1036")){r.next();assertThat(r.getInt(1)).isEqualTo(2320);}
                s.execute("UPDATE club_records SET financial_ledger_hex=NULL,financial_ledger_clock=NULL");c.commit();m.rollback(1,new Contexts(),new LabelExpression());
                try(var r=c.getMetaData().getColumns(null,null,"CLUB_RECORDS","FINANCIAL_LEDGER_HEX")){assertThat(r.next()).isFalse();}
                try(var r=s.executeQuery("SELECT name FROM club_records WHERE unique_id=1036")){r.next();assertThat(r.getString(1)).isEqualTo("sc Heerenveen");}
            }
        }
    }
}
