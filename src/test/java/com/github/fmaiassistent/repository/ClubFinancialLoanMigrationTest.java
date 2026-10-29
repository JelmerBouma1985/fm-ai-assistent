package com.github.fmaiassistent.repository;
import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;
class ClubFinancialLoanMigrationTest {
    @Test void expandsOldRowsDistinguishesEmptyAndRefusesPopulatedRollback()throws Exception {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:club-financial-loans-migration");var s=c.createStatement()) {
            s.execute("CREATE TABLE club_records(unique_id BIGINT PRIMARY KEY,kind VARCHAR(32),name VARCHAR(100))");
            s.execute("INSERT INTO club_records VALUES(1036,'CLUB','sc Heerenveen'),(7,'LEAGUE_BODY_CLUB','Association')");
            try(var migration=new Liquibase("db/changelog/changes/023-club-financial-loans.xml",new ClassLoaderResourceAccessor(),new JdbcConnection(c))) {
                migration.update(new Contexts(),new LabelExpression());
                try(var r=s.executeQuery("SELECT name,financial_loans_hex,financial_loan_clock FROM club_records WHERE unique_id=1036")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("sc Heerenveen");assertThat(r.getString(2)).isNull();assertThat(r.getObject(3)).isNull();}
                assertThatThrownBy(()->s.execute("UPDATE club_records SET financial_loans_hex='' WHERE unique_id=1036")).isInstanceOf(SQLException.class);
                assertThatThrownBy(()->s.execute("UPDATE club_records SET financial_loans_hex='',financial_loan_clock=133438093 WHERE unique_id=7")).isInstanceOf(SQLException.class);
                assertThatThrownBy(()->s.execute("UPDATE club_records SET financial_loans_hex='00',financial_loan_clock=133438093 WHERE unique_id=1036")).isInstanceOf(SQLException.class);
                s.execute("UPDATE club_records SET financial_loans_hex='',financial_loan_clock=133438093 WHERE unique_id=1036");c.commit();
                assertThatThrownBy(()->migration.rollback(1,new Contexts(),new LabelExpression())).isInstanceOf(Exception.class);
                try(var r=s.executeQuery("SELECT financial_loans_hex FROM club_records WHERE unique_id=1036")){r.next();assertThat(r.getString(1)).isEmpty();}
                s.execute("UPDATE club_records SET financial_loans_hex=NULL,financial_loan_clock=NULL");c.commit();migration.rollback(1,new Contexts(),new LabelExpression());
                try(var r=c.getMetaData().getColumns(null,null,"CLUB_RECORDS","FINANCIAL_LOANS_HEX")){assertThat(r.next()).isFalse();}
                try(var r=s.executeQuery("SELECT name FROM club_records WHERE unique_id=1036")){r.next();assertThat(r.getString(1)).isEqualTo("sc Heerenveen");}
            }
        }
    }
}
