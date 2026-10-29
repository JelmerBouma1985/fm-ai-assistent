package com.github.fmaiassistent.repository;
import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;
class AwardNameMigrationTest {
    @Test void expansionPreservesExistingTermsAndRollbackRefusesToLoseCapturedNames()throws Exception {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:award-name-migration");var s=c.createStatement()){
            s.execute("CREATE TABLE contract_terms(id INT PRIMARY KEY,raw_hex VARCHAR(32))");s.execute("INSERT INTO contract_terms VALUES(1,'abcd')");
            try(var m=new Liquibase("db/changelog/changes/028-contract-award-names.xml",new ClassLoaderResourceAccessor(),new JdbcConnection(c))){
                m.update(new Contexts(),new LabelExpression());
                s.execute("INSERT INTO award_name_records(unique_id,record_index,prefix_hex,module_sha256) VALUES(102406,0,REPEAT('0',32),REPEAT('e',64))");c.commit();
                assertThatThrownBy(()->m.rollback(1,new Contexts(),new LabelExpression())).isInstanceOf(Exception.class);
                try(var r=s.executeQuery("SELECT COUNT(*) FROM award_name_records")){r.next();assertThat(r.getInt(1)).isEqualTo(1);}
                s.execute("DELETE FROM award_name_records");c.commit();m.rollback(1,new Contexts(),new LabelExpression());
                try(var r=s.executeQuery("SELECT raw_hex FROM contract_terms WHERE id=1")){r.next();assertThat(r.getString(1)).isEqualTo("abcd");}
            }
        }
    }
}
