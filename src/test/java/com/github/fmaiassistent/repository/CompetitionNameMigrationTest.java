package com.github.fmaiassistent.repository;
import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;
class CompetitionNameMigrationTest {
    @Test void expansionPreservesLegacyRowsAndPopulatedRollbackIncludingNullNamesFails()throws Exception {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:competition-name-migration");var s=c.createStatement()) {
            s.execute("CREATE TABLE club_records(unique_id BIGINT PRIMARY KEY,name VARCHAR(100))");s.execute("INSERT INTO club_records VALUES(1036,'sc Heerenveen')");
            try(var m=new Liquibase("db/changelog/changes/026-financial-competition-names.xml",new ClassLoaderResourceAccessor(),new JdbcConnection(c))) {
                m.update(new Contexts(),new LabelExpression());
                try(var r=s.executeQuery("SELECT COUNT(*) FROM competition_name_records")){r.next();assertThat(r.getInt(1)).isZero();}
                s.execute("INSERT INTO competition_name_records(unique_id,record_index,source_address,prefix_hex,module_sha256) VALUES(29,20,10000000,'68fee045fe7f0000140000001d000000',REPEAT('e',64))");c.commit();
                assertThatThrownBy(()->m.rollback(1,new Contexts(),new LabelExpression())).isInstanceOf(Exception.class);
                try(var r=s.executeQuery("SELECT name,short_name FROM competition_name_records WHERE unique_id=29")){r.next();assertThat(r.getString(1)).isNull();assertThat(r.getString(2)).isNull();}
                s.execute("DELETE FROM competition_name_records");c.commit();m.rollback(1,new Contexts(),new LabelExpression());
                try(var r=s.executeQuery("SELECT name FROM club_records WHERE unique_id=1036")){r.next();assertThat(r.getString(1)).isEqualTo("sc Heerenveen");}
            }
        }
    }
}
