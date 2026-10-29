package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.domain.NativeClubSnapshot;
import com.github.fmaiassistent.domain.entity.ClubEntity;
import com.github.fmaiassistent.exporter.ClubExporter;
import com.github.fmaiassistent.memory.ClubFinanceLayout;
import com.github.fmaiassistent.service.SnapshotDatabaseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import java.nio.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties={"spring.jpa.show-sql=false","logging.level.liquibase=warn"})
@Import(SnapshotDatabaseWriter.class)
class ClubCurrentWagesPersistenceTest {
    @Autowired SnapshotDatabaseWriter writer;
    @Autowired ClubRepository clubs;
    @Test void atomicWriterEntityAndApiPreserveExactWagesAndUnknownAsNull() {
        var canonical=new ArrayList<NativeClubSnapshot>();var rows=new ArrayList<Map<String,Object>>();
        for(int i=0;i<2;i++) {
            long uid=i==0?1036:58,address=0x10000000L+i*0x1000;
            var prefix=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(0x7FFAA0800000L+0x44BA518).putInt(i).putInt((int)uid).array();
            canonical.add(new NativeClubSnapshot(uid,i,"CLUB","Club "+uid,address,HexFormat.of().formatHex(prefix)));
            var row=new HashMap<String,Object>();row.put("uniqueId",uid);row.put("sourceAddress",address);row.put("name","Club "+uid);
            if(i==0)row.put("currentWagesIncludingBonuses",1_763_603L);rows.add(row);
        }
        writer.saveClubs(new ClubExporter.ExportResult(rows,canonical,ClubFinanceLayout.MODULE_SHA256),Map.of());
        var first=clubs.findById(1L).orElseThrow();assertThat(first.getCurrentWagesIncludingBonuses()).isEqualTo(1_763_603L);
        assertThat(first.toApiMap()).containsEntry("CURRENT_WAGES_INCLUDING_BONUSES",1_763_603L);
        assertThat(clubs.findById(2L).orElseThrow().getCurrentWagesIncludingBonuses()).isNull();
        assertThat(ClubEntity.fromExportRow(Map.of("name","Legacy")).getCurrentWagesIncludingBonuses()).isNull();
    }
}
