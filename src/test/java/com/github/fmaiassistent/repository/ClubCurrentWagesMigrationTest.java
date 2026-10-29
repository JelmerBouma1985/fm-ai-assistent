package com.github.fmaiassistent.repository;

import liquibase.*;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

class ClubCurrentWagesMigrationTest {
    @Test void oldMoneySurvivesNullableExpansionAndPopulatedRollbackIsRefused()throws Exception {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:club-current-wages");var s=c.createStatement()) {
            s.execute("CREATE TABLE clubs(id BIGINT PRIMARY KEY,balance BIGINT)");s.execute("INSERT INTO clubs VALUES(1,213254902)");
            try(var l=new Liquibase("db/changelog/changes/022-club-current-wages.xml",new ClassLoaderResourceAccessor(),new JdbcConnection(c))) {
                l.update(new Contexts(),new LabelExpression());
                try(var r=s.executeQuery("SELECT balance,current_wages_including_bonuses FROM clubs")){r.next();assertThat(r.getLong(1)).isEqualTo(213254902);assertThat(r.getObject(2)).isNull();}
                s.execute("UPDATE clubs SET current_wages_including_bonuses=1763603");c.commit();
                assertThatThrownBy(()->l.rollback(1,new Contexts(),new LabelExpression())).isInstanceOf(Exception.class);
                try(var r=s.executeQuery("SELECT current_wages_including_bonuses FROM clubs")){r.next();assertThat(r.getLong(1)).isEqualTo(1763603);}
                s.execute("UPDATE clubs SET current_wages_including_bonuses=NULL");c.commit();l.rollback(1,new Contexts(),new LabelExpression());
                try(var r=c.getMetaData().getColumns(null,null,"CLUBS","CURRENT_WAGES_INCLUDING_BONUSES")){assertThat(r.next()).isFalse();}
                try(var r=s.executeQuery("SELECT balance FROM clubs")){r.next();assertThat(r.getLong(1)).isEqualTo(213254902);}
            }
        }
    }
}
