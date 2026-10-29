package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.memory.CompetitionRecordLayout;
import com.github.fmaiassistent.memory.ProcessReaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("fm26-live")
@EnabledIfSystemProperty(named="fm26.club.references",matches="true")
class ClubCompetitionReferenceLiveTest {
    @Test void identifyNativeCompetitionFiltersResponsibleForMissingClubRelations()throws Exception {
        int pid=Integer.getInteger("fm26.live.pid",-1);assertThat(pid).isPositive();
        String url=System.getProperty("fm26.delivery.database.url");assertThat(url).contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        var present=new HashSet<Long>(jdbc.queryForList("SELECT source_address FROM competitions",Long.class));
        var exported=new ClubExporter().exportAllClubs(pid,0x238BDD,null);
        var missing=new LinkedHashMap<Long,Map<String,Object>>();long affected=0;
        try(var reader=ProcessReaders.open(pid)) {
            long base=FmOffsets.findGamePluginBase(reader),descriptor=reader.readU64(base+0x4E49590);
            long header=reader.readU64(descriptor+0x80),begin=reader.readU64(header),end=reader.readU64(header+8);
            var layout=CompetitionRecordLayout.current();
            for(var row:exported.rows()) {
                long target=((Number)row.get("_competition_address")).longValue();if(present.contains(target))continue;
                affected++;if(missing.containsKey(target))continue;
                int index=reader.readI32(target+8);assertThat(index).isBetween(0,(int)((end-begin)/8)-1);
                assertThat(reader.readU64(begin+index*8L)).isEqualTo(target);assertThat(reader.readU64(target)-base).isEqualTo(0x44BFE68L);
                var item=new LinkedHashMap<String,Object>();item.put("address",Long.toHexString(target));
                item.put("uid",reader.readU32(target+12));item.put("name",row.get("competition"));
                item.put("rawReputation",reader.readU16(target+layout.reputationRel()));
                item.put("nationPointer",Long.toHexString(reader.readU64(target+layout.nationRel())));
                item.put("currentDecoderReturnsEmpty",new CompetitionExporter().decodeCompetition(reader,target).isEmpty());
                item.put("prefixHex",HexFormat.of().formatHex(reader.readBytes(target,16)));missing.put(target,item);
            }
            assertThat(reader.readU64(header)).isEqualTo(begin);assertThat(reader.readU64(header+8)).isEqualTo(end);
        }
        var report=new LinkedHashMap<String,Object>();report.put("pid",pid);report.put("clubRows",exported.rows().size());
        report.put("affectedClubRows",affected);report.put("missingCompetitions",missing.values());
        report.put("limitation","Missing lookup nodes investigated for club references only; no broader competition-domain audit");
        Files.write(Path.of(System.getProperty("fm26.club.references.report")),JsonMapper.builder().build().writeValueAsBytes(report),StandardOpenOption.CREATE_NEW);
    }
}
