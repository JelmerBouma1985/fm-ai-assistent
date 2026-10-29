package com.github.fmaiassistent.web.ui;
import com.github.fmaiassistent.exporter.ContractExporter;
import com.github.fmaiassistent.repository.ContractRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("fm26-live")
@EnabledIfSystemProperty(named="fm26.competition.bonuses.database",matches="true")
class CompetitionBonusApplicationLiveTest {
    @Test void actualSnapshotKeepsAll863RawRowsAndDistinctReferencesWithNamesAndViews()throws Exception {
        String url=System.getProperty("fm26.competition.bonuses.database.url");assertThat(url).contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));var repository=new ContractRepository(jdbc);
        var file=Path.of("docs/reverse-engineering/fm26/evidence/competition_bonus_references_checkpoint56_verified_v3_20261027.json");
        byte[] bytes;try(var in=Files.newInputStream(file)){bytes=in.readNBytes(2*1024*1024+1);}assertThat(bytes.length).isLessThanOrEqualTo(2*1024*1024);
        var expected=tools.jackson.databind.json.JsonMapper.builder().build().readTree(bytes);var wanted=new TreeMap<String,Integer>();
        for(var r:expected.get("normalizedRows")) {
            String key=r.get("personUid").asLong()+"|"+r.get("source").asString()+"|"+r.get("ordinal").asInt()+"|"+r.get("rawHex").asString().toLowerCase(Locale.ROOT)
                    +"|"+(r.get("cbcpCompetitionUid").isNull()?null:r.get("cbcpCompetitionUid").asLong())+"|"+(r.get("conditionCompetitionUid").isNull()?null:r.get("conditionCompetitionUid").asLong());
            wanted.merge(key,1,Integer::sum);
        }
        var actual=new TreeMap<String,Integer>();int rows=0;
        for(var r:jdbc.queryForList("SELECT c.owner_uid,c.source,c.module_sha256,t.* FROM contract_terms t JOIN player_contracts c ON c.id=t.contract_id WHERE t.term_group='FULL_80'")) {
            assertThat(r.get("MODULE_SHA256")).isEqualTo(ContractExporter.MODULE_SHA256);
            String key=r.get("OWNER_UID")+"|"+r.get("SOURCE")+"|"+r.get("ORDINAL")+"|"+((String)r.get("RAW_HEX")).toLowerCase(Locale.ROOT)+"|"+r.get("SUBJECT_COMPETITION_UID")+"|"+r.get("CONDITION_COMPETITION_UID");
            actual.merge(key,1,Integer::sum);rows++;
        }
        assertThat(rows).isEqualTo(863);assertThat(actual).isEqualTo(wanted);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM competition_name_records",Integer.class)).isEqualTo(64);
        var views=new LinkedHashMap<Long,Object>();
        for(long uid:List.of(2002070983L,43390774L)) {
            long id=jdbc.queryForObject("SELECT id FROM players WHERE unique_id=?",Long.class,Long.toString(uid));
            var details=new ArrayList<ContractDetailsPanel.Detail>();for(var c:repository.findPlayerContracts(id,uid))
                details.addAll(ContractDetailsPanel.details(c.snapshot(),n->"RAW "+n,true,c.awardNames(),c.competitionNames()));
            assertThat(details).anyMatch(d->d.label().contains("UEFA Champions League")&&d.value().startsWith(uid==2002070983L?"RAW 108779":"RAW 35072"));
            views.put(uid,details);
        }
        String output=System.getProperty("fm26.competition.bonuses.database.report");if(output!=null)Files.write(Path.of(output),
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(Map.of("snapshot",jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='snapshot_id'",String.class),
                        "rawRows",rows,"competitionNames",64,"allRowsAndReferencesMatchIndependentEvidence",true,"selectedViews",views)),StandardOpenOption.CREATE_NEW);
    }
}
