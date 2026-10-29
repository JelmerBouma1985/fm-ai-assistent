package com.github.fmaiassistent.repository;

import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

class NonPlayingProfileMigrationTest {
    @Test void preservesOldOwnersEnforcesSourcePairAndRefusesPopulatedRollback()throws Exception {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:nonplaying-profile-migration");var s=c.createStatement()) {
            s.execute("CREATE TABLE players(id BIGINT PRIMARY KEY,name VARCHAR(100))");s.execute("CREATE TABLE staff(id BIGINT PRIMARY KEY,name VARCHAR(100))");
            s.execute("INSERT INTO players VALUES(1,'Combined player')");s.execute("INSERT INTO staff VALUES(2,'Coach')");
            try(var migration=new Liquibase("db/changelog/changes/020-non-playing-profiles.xml",new ClassLoaderResourceAccessor(),new JdbcConnection(c))) {
                migration.update(new Contexts(),new LabelExpression());
                try(var r=s.executeQuery("SELECT name,non_playing_profile_hex FROM players WHERE id=1")){r.next();assertThat(r.getString(1)).isEqualTo("Combined player");assertThat(r.getString(2)).isNull();}
                assertThatThrownBy(()->s.execute("UPDATE players SET non_playing_profile_hex='"+"01".repeat(52)+"' WHERE id=1")).isInstanceOf(SQLException.class);
                s.execute("UPDATE players SET non_playing_profile_hex='"+"01".repeat(52)+"',non_playing_profile_sha256='"+"a".repeat(64)+"' WHERE id=1");
                c.commit();
                assertThatThrownBy(()->migration.rollback(1,new Contexts(),new LabelExpression())).isInstanceOf(Exception.class);
                try(var r=s.executeQuery("SELECT non_playing_profile_hex FROM players WHERE id=1")){r.next();assertThat(r.getString(1)).hasSize(104);}
                s.execute("UPDATE players SET non_playing_profile_hex=NULL,non_playing_profile_sha256=NULL");
                c.commit();
                migration.rollback(1,new Contexts(),new LabelExpression());
                try(var r=c.getMetaData().getColumns(null,null,"PLAYERS","NON_PLAYING_PROFILE_HEX")){assertThat(r.next()).isFalse();}
                try(var r=s.executeQuery("SELECT name FROM staff WHERE id=2")){r.next();assertThat(r.getString(1)).isEqualTo("Coach");}
            }
        }
    }
}
