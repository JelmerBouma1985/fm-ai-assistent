package com.github.fmaiassistent.web.ui;
import com.github.fmaiassistent.exporter.*;
import com.github.fmaiassistent.domain.NativeCompetitionNameSnapshot;
import com.github.fmaiassistent.memory.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("fm26-live")
class CompetitionNameApplicationLiveTest {
    @Test @EnabledIfSystemProperty(named="fm26.competition.names.live",matches="true")
    void actualProductionCaptureMatchesAll60IndependentNamesAndKeepsAllFinancialPayloads()throws Exception {
        int pid=Integer.parseInt(System.getProperty("fm26.competition.names.pid","0"));assertThat(pid).isEqualTo(22268);
        var expected=CompetitionNameCaptureTest.observedNames().stream().collect(java.util.stream.Collectors.toMap(NativeCompetitionNameSnapshot::uniqueId,n->n));long start=System.nanoTime();
        var result=new ClubExporter().exportAllClubs(pid,0x238BDD,null);assertThat(result.competitionNames()).hasSize(60);int absent=0;
        for(var n:result.competitionNames()){assertThat(n).isEqualTo(expected.get(n.uniqueId()));if(n.nameHex()==null&&n.shortNameHex()==null)absent++;}
        assertThat(absent).isEqualTo(20);var vectors=ClubFinancialCompetitionCaptureTest.expectedPayloads("financial_competition_vectors_population_fm22268_20261027.csv","financial_competition_keys_population_fm22268_20261027.csv");int profiles=0;
        for(var r:result.nativeRecords()){assertThat(r.financialCompetitionsHex()).isEqualTo(vectors.get(r.uniqueId()));if(r.financialCompetitionsHex()!=null)profiles++;}
        assertThat(profiles).isEqualTo(680);assertThat(result.nativeRecords()).hasSize(26839);
        report("fm26.competition.names.live.report",Map.of("pid",pid,"references",60,"named",40,"bothDirectNamesNull",20,"allNameAndIdentityFieldsMatchIndependentCapture",true,"all680FinancialPayloadsUnchanged",true,"elapsedMillis",(System.nanoTime()-start)/1_000_000));
    }
    @Test @EnabledIfSystemProperty(named="fm26.competition.names.database",matches="true")
    void actualAtomicSnapshotContainsAll60ReferencesIncluding33CatalogueOmissionsAndAllAmounts()throws Exception {
        String url=System.getProperty("fm26.delivery.database.url");assertThat(url).contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));assertThat(jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='fm_pid'",String.class)).isEqualTo("22268");
        var expected=CompetitionNameCaptureTest.observedNames().stream().collect(java.util.stream.Collectors.toMap(NativeCompetitionNameSnapshot::uniqueId,n->n));int references=0,absent=0;
        for(var row:jdbc.queryForList("SELECT * FROM competition_name_records")){references++;var e=expected.get(((Number)row.get("UNIQUE_ID")).longValue());assertThat(e).isNotNull();
            assertThat(row.get("RECORD_INDEX")).isEqualTo(e.recordIndex());assertThat(((Number)row.get("SOURCE_ADDRESS")).longValue()).isEqualTo(e.sourceAddress());assertThat(row.get("PREFIX_HEX")).isEqualTo(e.prefixHex());assertThat(row.get("MODULE_SHA256")).isEqualTo(ClubFinancialLedgerLayout.MODULE_SHA256);
            assertThat(row.get("NAME_HEX")).isEqualTo(e.nameHex());assertThat(row.get("SHORT_NAME_HEX")).isEqualTo(e.shortNameHex());assertThat(row.get("NAME")).isEqualTo(e.name());assertThat(row.get("SHORT_NAME")).isEqualTo(e.shortName());if(e.nameHex()==null)absent++;}
        assertThat(references).isEqualTo(60);assertThat(absent).isEqualTo(20);
        var vectors=ClubFinancialCompetitionCaptureTest.expectedPayloads("financial_competition_vectors_population_fm22268_20261027.csv","financial_competition_keys_population_fm22268_20261027.csv");int profiles=0,members=0;
        for(var row:jdbc.queryForList("SELECT unique_id,module_sha256,financial_competitions_hex,financial_ledger_hex,financial_ledger_clock FROM club_records")){long uid=((Number)row.get("UNIQUE_ID")).longValue();String raw=(String)row.get("FINANCIAL_COMPETITIONS_HEX");assertThat(raw).isEqualTo(vectors.get(uid));if(raw==null)continue;profiles++;
            var p=ClubFinancialCompetitionLayout.values(raw,((Number)row.get("FINANCIAL_LEDGER_CLOCK")).intValue(),(String)row.get("MODULE_SHA256"),(String)row.get("FINANCIAL_LEDGER_HEX")).orElseThrow();members+=p.periods().stream().mapToInt(v->v.tvRevenue().size()+v.prizeMoney().size()).sum();}
        assertThat(profiles).isEqualTo(680);assertThat(members).isEqualTo(2747);
        int omitted=jdbc.queryForObject("SELECT COUNT(*) FROM competition_name_records n WHERE NOT EXISTS(SELECT 1 FROM competitions c WHERE c.source_address=n.source_address)",Integer.class);assertThat(omitted).isEqualTo(33);
        var preserved=new LinkedHashMap<String,Integer>();for(var p:Map.of("players",44467,"staff",25059,"player_contracts",59533,"contract_terms",188254,"competitions",6619).entrySet()){int count=jdbc.queryForObject("SELECT COUNT(*) FROM "+p.getKey(),Integer.class);assertThat(count).isEqualTo(p.getValue());preserved.put(p.getKey(),count);}
        byte[] input;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/financial_competition_unnamed_members_20261027.json"))){input=in.readNBytes(1048577);}assertThat(input.length).isLessThanOrEqualTo(1048576);
        var observations=new tools.jackson.databind.json.JsonMapper().readTree(input).path("rows");assertThat(observations.size()).isEqualTo(21);Map<String,Object> sample=null;
        for(var n:observations) {
            long uid=n.path("competitionUid").asLong(),club=n.path("clubUid").asLong();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM competition_name_records WHERE unique_id=? AND name IS NULL AND short_name IS NULL",Integer.class,uid)).isOne();
            var record=jdbc.queryForMap("SELECT financial_competitions_hex,financial_ledger_clock FROM club_records WHERE unique_id=?",club);
            var profile=ClubFinancialCompetitionLayout.values((String)record.get("FINANCIAL_COMPETITIONS_HEX"),((Number)record.get("FINANCIAL_LEDGER_CLOCK")).intValue(),ClubFinancialLedgerLayout.MODULE_SHA256).orElseThrow();
            var period=profile.periods().get(n.path("period").asInt());var entries=n.path("kind").asInt()==0?period.tvRevenue():period.prizeMoney();var member=entries.get(n.path("ordinal").asInt());assertThat(member.competitionUid()).isEqualTo(uid);assertThat(member.amount()).isEqualTo(n.path("amount").asInt()).isNotZero();
            if(sample==null){var catalogue=jdbc.queryForList("SELECT DISTINCT name FROM clubs WHERE unique_id=? AND name IS NOT NULL",String.class,club);
                if(!catalogue.isEmpty())sample=Map.of("clubUid",club,"clubName",catalogue.getFirst(),"competitionUid",uid,"amount",member.amount(),"period",period.index(),"kind",n.path("kind").asInt());}
        }
        assertThat(sample).isNotNull();var report=new LinkedHashMap<String,Object>();report.put("references",references);report.put("named",40);report.put("bothDirectNamesNull",absent);report.put("referencesOutsideCatalogue",omitted);report.put("allNameAndIdentityFieldsMatchIndependentCapture",true);report.put("financialProfiles",profiles);report.put("financialMembers",members);report.put("preserved",preserved);report.put("all21NamelessNonzeroMembersPreserved",true);report.put("namelessUiSample",sample);report.put("snapshot",jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='snapshot_id'",String.class));report("fm26.competition.names.database.report",report);
    }
    static void report(String property,Map<String,Object> data)throws Exception {String name=System.getProperty(property);assertThat(name).isNotBlank();Files.writeString(Path.of(name),new tools.jackson.databind.json.JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(data),StandardOpenOption.CREATE_NEW);}
}
