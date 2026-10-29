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
import static org.assertj.core.api.Assertions.*;

class FutureContractSourceMigrationTest {
    @Test void preservesCurrentPlayerAndStaffRowsAndRefusesRollbackBeforeLosingFutureData()throws Exception {
        String change="db/changelog/changes/018-future-employment-contracts.xml";
        try(var c=DriverManager.getConnection("jdbc:h2:mem:future-source-migration;DB_CLOSE_DELAY=-1");var s=c.createStatement()) {
            s.execute("CREATE TABLE player_contracts(id BIGINT PRIMARY KEY,player_id BIGINT,staff_id BIGINT,slot INT NOT NULL,core_hex VARCHAR(496),UNIQUE(player_id,slot),UNIQUE(staff_id,slot))");
            s.execute("INSERT INTO player_contracts VALUES(1,1,NULL,-1,'old-player'),(2,NULL,1,-1,'old-staff')");
            try(var l=new Liquibase(change,new ClassLoaderResourceAccessor(),new JdbcConnection(c))) {
                l.update(new Contexts(),new LabelExpression());l.update(new Contexts(),new LabelExpression());
                assertThat(count(s,"source='PERSON_LINK' AND slot=-1 AND offer_index IS NULL")).isEqualTo(2);
                String cols="id,player_id,staff_id,slot,core_hex,source,offer_index,future_end_date,future_wage_raw,future_record_hex,future_record_state,future_end_uses_game_date";
                s.execute("INSERT INTO player_contracts("+cols+") VALUES(3,1,NULL,NULL,'new-player','FUTURE_EMPLOYMENT',74,DATE '2038-06-30',4109,'abcd',2,FALSE),(4,NULL,1,NULL,'new-staff','FUTURE_EMPLOYMENT',74,DATE '2038-06-30',4109,'abcd',2,FALSE)");
                c.commit();
                assertThatThrownBy(()->s.execute("INSERT INTO player_contracts("+cols+") VALUES(5,1,NULL,NULL,'duplicate','FUTURE_EMPLOYMENT',74,DATE '2038-06-30',4109,'abcd',2,FALSE)")).isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(()->s.execute("UPDATE player_contracts SET slot=0 WHERE id=3")).isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(()->s.execute("UPDATE player_contracts SET offer_index=75 WHERE id=1")).isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(()->l.rollback(1,new Contexts(),new LabelExpression())).isInstanceOf(liquibase.exception.LiquibaseException.class);
                assertThat(count(s,"source='FUTURE_EMPLOYMENT' AND core_hex LIKE 'new-%'")).isEqualTo(2);
                s.execute("DELETE FROM player_contracts WHERE source='FUTURE_EMPLOYMENT'");c.commit();
                l.rollback(1,new Contexts(),new LabelExpression());
                assertThat(count(s,"core_hex LIKE 'old-%' AND slot=-1")).isEqualTo(2);
                try(var colsResult=c.getMetaData().getColumns(null,null,"PLAYER_CONTRACTS","SOURCE")){assertThat(colsResult.next()).isFalse();}
            }
        }
        Scope.getCurrentScope().getSingleton(FastCheckService.class).clearCache();
        try(var c=DriverManager.getConnection("jdbc:h2:mem:future-source-migration;DB_CLOSE_DELAY=-1");var l=new Liquibase(change,new ClassLoaderResourceAccessor(),new JdbcConnection(c))) {
            l.update(new Contexts(),new LabelExpression());assertThat(count(c.createStatement(),"source='PERSON_LINK' AND slot=-1")).isEqualTo(2);
        }
    }
    private static int count(java.sql.Statement s,String where)throws Exception {
        try(var r=s.executeQuery("SELECT COUNT(*) FROM player_contracts WHERE "+where)){r.next();return r.getInt(1);}
    }
}
