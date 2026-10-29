package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.NonPlayingProfileLayout;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("fm26-live")
@EnabledIfSystemProperty(named="fm26.nonplaying.live",matches="true")
class NonPlayingProfileDeliveryLiveTest {
    @Test void normalPeopleExporterPreservesEveryCapturedCombinedProfileAndNativeStaffOwnership()throws Exception {
        int pid=Integer.getInteger("fm26.live.pid",-1);assertThat(pid).isPositive();long started=System.nanoTime();
        var result=new PeopleExporter().exportAllPeople(pid,0x238BDD,null);
        var file=Path.of("docs/reverse-engineering/fm26/evidence/all_combined_profiles_live_20261025.csv");
        byte[] raw;try(var in=Files.newInputStream(file)){raw=in.readNBytes(1048577);}assertThat(raw.length).isLessThanOrEqualTo(1048576);
        var expected=new HashMap<Long,String>();new String(raw,java.nio.charset.StandardCharsets.UTF_8).lines().skip(1).forEach(line->{var c=line.split(",",-1);expected.put(Long.parseLong(c[1]),c[7].substring(16,120));});
        byte[] humanBytes;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/human_nonplaying_profiles_verified_20261025.json"))){humanBytes=in.readNBytes(65537);}assertThat(humanBytes.length).isLessThanOrEqualTo(65536);
        var humanRaw=new HashMap<Long,String>();for(var h:JsonMapper.builder().build().readTree(humanBytes).get("samples"))humanRaw.put(h.get("uid").asLong(),h.get("profileRaw52").asString());
        int playerProfiles=0,staffProfiles=0,humanProfiles=0;var samples=new ArrayList<Map<String,Object>>();var actual=new HashSet<Long>();
        for(var row:result.players().rows())if(row.get("non_playing_profile_hex") instanceof String payload){
            long uid=((Number)row.get("unique_id")).longValue();assertThat(actual.add(uid)).isTrue();assertThat(payload).isEqualTo(expected.get(uid));
            assertThat(NonPlayingProfileLayout.values(payload,(String)row.get("non_playing_profile_sha256"))).hasSize(50);playerProfiles++;
            if(uid==222138||uid==253308)samples.add(Map.of("kind","PLAYER","uid",uid,"name",row.get("name"),"raw52",payload));
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected.keySet());assertThat(playerProfiles).isEqualTo(1408);
        for(var row:result.staff().rows())if(row.get("non_playing_profile_hex") instanceof String payload){
            assertThat(expected).doesNotContainKey(((Number)row.get("unique_id")).longValue());
            assertThat(NonPlayingProfileLayout.values(payload,(String)row.get("non_playing_profile_sha256"))).hasSize(50);staffProfiles++;
            long uid=((Number)row.get("unique_id")).longValue();if(humanRaw.containsKey(uid)){assertThat(payload).isEqualTo(humanRaw.get(uid));humanProfiles++;samples.add(Map.of("kind","HUMAN","uid",uid,"name",row.get("name"),"raw52",payload));}
            if(samples.size()<3)samples.add(Map.of("kind","STAFF","uid",row.get("unique_id"),"name",row.get("name"),"raw52",payload));
        }
        assertThat(staffProfiles).isEqualTo(25059);assertThat(humanProfiles).isEqualTo(2);assertThat(result.players().rows()).hasSize(44467);assertThat(result.staff().rows()).hasSize(25059);
        var report=new LinkedHashMap<String,Object>();report.put("pid",pid);report.put("moduleSha256",NonPlayingProfileLayout.MODULE_SHA256);report.put("elapsedMillis",(System.nanoTime()-started)/1000000);
        report.put("players",result.players().rows().size());report.put("staff",result.staff().rows().size());report.put("combinedPlayerProfiles",playerProfiles);report.put("standaloneStaffProfiles",staffProfiles-humanProfiles);report.put("allStaffProfiles",staffProfiles);report.put("humanProfiles",humanProfiles);report.put("unprovedHumanProfilesOmitted",0);report.put("all1408CapturedPayloadsMatch",true);report.put("allCapturedHumanPayloadsMatch",true);report.put("samples",samples);
        report.put("limit","Read-only normal production export for this save; not a database refresh or reload/restart proof. Native names partial, values raw with20/100storage scale.");
        Files.write(Path.of(System.getProperty("fm26.nonplaying.report")),JsonMapper.builder().build().writeValueAsBytes(report),StandardOpenOption.CREATE_NEW);
    }
}
