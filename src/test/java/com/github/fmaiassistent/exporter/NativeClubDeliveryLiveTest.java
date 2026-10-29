package com.github.fmaiassistent.exporter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("fm26-live")
@EnabledIfSystemProperty(named="fm26.native.clubs.live",matches="true")
class NativeClubDeliveryLiveTest {
    @Test void canonicalExportMatchesIndependentCapturedRegistryWithoutChangingContextViews()throws Exception {
        int pid=Integer.getInteger("fm26.live.pid",-1);assertThat(pid).isPositive();
        long started=System.nanoTime();var result=new ClubExporter().exportAllClubs(pid,0x238BDD,null);
        assertThat(result.identityProfile()).isEqualTo(ContractExporter.MODULE_SHA256);
        var path=Path.of("docs/reverse-engineering/fm26/evidence/club_registry_prefixes_live_20261025.csv");
        assertThat(Files.size(path)).isLessThan(4L*1024*1024);
        var expected=new HashMap<Long,String[]>();
        for(var line:Files.readAllLines(path).subList(1,26840)) {
            var row=line.split(",",-1);assertThat(expected.put(Long.parseUnsignedLong(row[2],16),row)).isNull();
        }
        assertThat(result.nativeRecords()).hasSize(expected.size());
        var byAddress=new HashMap<Long,Long>();long unnamed=0,leagueBodies=0;
        for(var record:result.nativeRecords()) {
            var raw=expected.get(record.sourceAddress());assertThat(raw).isNotNull();
            assertThat(record.recordIndex()).isEqualTo(Integer.parseInt(raw[0]));
            assertThat(record.uniqueId()).isEqualTo(Long.parseLong(raw[1]));assertThat(record.prefixHex()).isEqualTo(raw[4]);
            assertThat(record.kind()).isEqualTo(raw[3].equals("44BA518")?"CLUB":"LEAGUE_BODY_CLUB");
            byAddress.put(record.sourceAddress(),record.uniqueId());
            if(record.name()==null||record.name().isBlank())unnamed++;if(record.kind().equals("LEAGUE_BODY_CLUB"))leagueBodies++;
        }
        assertThat(result.rows()).hasSize(31610);
        for(var row:result.rows())assertThat(((Number)row.get("uniqueId")).longValue()).isEqualTo(byAddress.get(((Number)row.get("sourceAddress")).longValue()));
        var report=new LinkedHashMap<String,Object>();report.put("pid",pid);report.put("profile",result.identityProfile());
        report.put("nativeRecords",result.nativeRecords().size());report.put("leagueBodies",leagueBodies);report.put("unnamed",unnamed);
        report.put("contextViews",result.rows().size());report.put("elapsedMillis",(System.nanoTime()-started)/1000000);
        report.put("allCapturedIdentitiesAndViewUidsMatch",true);
        report.put("sample",result.nativeRecords().stream().filter(r->r.uniqueId()==58||r.uniqueId()==630||r.uniqueId()==731).toList());
        report.put("limitation","Exact identity/prefix/view membership for this process; display-name decoder reused; not all CLUB fields or reload stability.");
        String output=System.getProperty("fm26.native.clubs.report");
        if(output!=null)Files.write(Path.of(output),JsonMapper.builder().build().writeValueAsBytes(report),StandardOpenOption.CREATE_NEW);
    }
}
