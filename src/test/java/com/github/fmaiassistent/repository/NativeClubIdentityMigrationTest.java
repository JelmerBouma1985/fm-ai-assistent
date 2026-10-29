package com.github.fmaiassistent.repository;

import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

class NativeClubIdentityMigrationTest {
    @Test void preservesOldViewsAllowsAliasesAndRefusesLossOfCanonicalRecords() throws Exception {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:native-club-migration");var s=c.createStatement()) {
            s.execute("CREATE TABLE clubs(id BIGINT PRIMARY KEY,name VARCHAR(100))");
            s.execute("INSERT INTO clubs VALUES(1,'Old view'),(2,'Other view')");
            try(var migration=new Liquibase("db/changelog/changes/019-native-club-identities.xml",new ClassLoaderResourceAccessor(),new JdbcConnection(c))) {
                migration.update(new Contexts(),new LabelExpression());
                try(var r=s.executeQuery("SELECT COUNT(*) FROM clubs WHERE unique_id IS NULL")){r.next();assertThat(r.getInt(1)).isEqualTo(2);}
                s.execute("INSERT INTO club_records VALUES(58,0,'CLUB','Heerenveen',65536,'0000000000000000000000003a000000','hash'),(77,1,'LEAGUE_BODY_CLUB',NULL,65552,'0000000000000000010000004d000000','hash')");
                s.execute("UPDATE clubs SET unique_id=58"); // Two contextual views of one native object are valid.
                try(var p=c.prepareStatement("MERGE INTO club_records (unique_id,record_index,kind,name,source_address,prefix_hex,module_sha256) KEY(unique_id) VALUES (?,?,?,?,?,?,?)")) {
                    Object[] values={58L,0,"CLUB","Heerenveen",65536L,"0000000000000000000000003a000000","hash"};
                    for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);p.executeUpdate();
                }
                assertThatThrownBy(()->s.execute("UPDATE clubs SET unique_id=999 WHERE id=1")).isInstanceOf(SQLException.class);
                c.commit();
                assertThatThrownBy(()->migration.rollback(1,new Contexts(),new LabelExpression())).isInstanceOf(liquibase.exception.LiquibaseException.class);
                try(var r=s.executeQuery("SELECT COUNT(*) FROM club_records")){r.next();assertThat(r.getInt(1)).isEqualTo(2);}
                try(var r=s.executeQuery("SELECT COUNT(*) FROM clubs WHERE unique_id=58")){r.next();assertThat(r.getInt(1)).isEqualTo(2);}
                s.execute("UPDATE clubs SET unique_id=NULL");s.execute("DELETE FROM club_records");c.commit();
                migration.rollback(1,new Contexts(),new LabelExpression());
                try(var r=s.executeQuery("SELECT name FROM clubs WHERE id=1")){r.next();assertThat(r.getString(1)).isEqualTo("Old view");}
                try(var r=c.getMetaData().getColumns(null,null,"CLUBS","UNIQUE_ID")){assertThat(r.next()).isFalse();}
            }
        }
    }
}
