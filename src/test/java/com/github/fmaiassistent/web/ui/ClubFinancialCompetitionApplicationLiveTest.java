package com.github.fmaiassistent.web.ui;
import com.github.fmaiassistent.exporter.*;
import com.github.fmaiassistent.memory.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit live/in-use database opt-in; independent CE fixture selected by safe file stem. */
@Tag("fm26-live")
class ClubFinancialCompetitionApplicationLiveTest {
    static Map<Long,String> expected()throws Exception {
        String capture=System.getProperty("fm26.financial.competitions.capture","");assertThat(capture).matches("fm[0-9]+_[0-9]{8}");
        return ClubFinancialCompetitionCaptureTest.expectedPayloads("financial_competition_vectors_population_"+capture+".csv","financial_competition_keys_population_"+capture+".csv");
    }
    @Test @EnabledIfSystemProperty(named="fm26.financial.competitions.live",matches="true")
    void actualProductionExportMatchesAllIndependentCapturedMembersAndSuffixes()throws Exception {
        int pid=Integer.parseInt(System.getProperty("fm26.financial.competitions.pid","0"));assertThat(pid).isPositive();
        var expected=expected();long started=System.nanoTime();var result=new ClubExporter().exportAllClubs(pid,0x238BDD,null);
        assertThat(result.identityProfile()).isEqualTo(ClubFinancialLedgerLayout.MODULE_SHA256);int owners=0,members=0;
        for(var r:result.nativeRecords()){assertThat(r.financialCompetitionsHex()).isEqualTo(expected.get(r.uniqueId()));
            if(r.financialCompetitionsHex()!=null){owners++;var p=ClubFinancialCompetitionLayout.values(r.financialCompetitionsHex(),r.financialLedgerClock(),result.identityProfile(),r.financialLedgerHex()).orElseThrow();
                members+=p.periods().stream().mapToInt(v->v.tvRevenue().size()+v.prizeMoney().size()).sum();}}
        assertThat(result.nativeRecords()).hasSize(26839);assertThat(owners).isEqualTo(680);assertThat(members).isEqualTo(2747);
        report("fm26.financial.competitions.live.report",Map.of("pid",pid,"canonicalClubs",26839,"profiles",owners,"members",members,"allNormalizedUidAmountSuffixPayloadsMatch",true,"elapsedMillis",(System.nanoTime()-started)/1_000_000));
    }
    @Test @EnabledIfSystemProperty(named="fm26.financial.competitions.database",matches="true")
    void actualNormalDatabaseSnapshotMatchesAllCollectionsAndKeepsExistingEntities()throws Exception {
        var expected=expected();String url=System.getProperty("fm26.delivery.database.url");assertThat(url).contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));int clubs=0,owners=0,members=0;
        for(var row:jdbc.queryForList("SELECT unique_id,module_sha256,financial_ledger_hex,financial_ledger_clock,financial_competitions_hex FROM club_records")) {
            clubs++;long uid=((Number)row.get("UNIQUE_ID")).longValue();String raw=(String)row.get("FINANCIAL_COMPETITIONS_HEX");assertThat(raw).isEqualTo(expected.get(uid));if(raw==null)continue;
            owners++;var p=ClubFinancialCompetitionLayout.values(raw,((Number)row.get("FINANCIAL_LEDGER_CLOCK")).intValue(),(String)row.get("MODULE_SHA256"),(String)row.get("FINANCIAL_LEDGER_HEX")).orElseThrow();
            members+=p.periods().stream().mapToInt(v->v.tvRevenue().size()+v.prizeMoney().size()).sum();
        }
        assertThat(clubs).isEqualTo(26839);assertThat(owners).isEqualTo(680);assertThat(members).isEqualTo(2747);
        var preserved=new LinkedHashMap<String,Integer>();for(var pair:Map.of("players",44467,"staff",25059,"player_contracts",59533,"contract_terms",188254).entrySet()) {
            int count=jdbc.queryForObject("SELECT COUNT(*) FROM "+pair.getKey(),Integer.class);assertThat(count).isEqualTo(pair.getValue());preserved.put(pair.getKey(),count);}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM club_records WHERE financial_ledger_hex IS NOT NULL",Integer.class)).isEqualTo(680);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM club_records WHERE uefa_coefficient_hex IS NOT NULL",Integer.class)).isEqualTo(1903);
        var oldLoans=new HashMap<Long,String>();for(var r:ClubFinancialLoanCaptureTest.rows())oldLoans.put(Long.parseLong(r[1]),ClubFinancialLoanCaptureTest.payload(r));
        var oldUefa=new HashMap<Long,String>();byte[] csv;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/club_uefa_coefficients_population_live_20261026.csv"))){csv=in.readNBytes(8388609);}assertThat(csv.length).isLessThanOrEqualTo(8388608);
        new String(csv,java.nio.charset.StandardCharsets.US_ASCII).lines().skip(1).map(s->s.split(",",-1)).filter(r->!r[6].isEmpty()).forEach(r->oldUefa.put(Long.parseLong(r[1]),r[6]));
        int loanRecords=0,loanOwners=0,unknown19Changes=0,unknown1bChanges=0,uefaProfiles=0;var uefaRawDifferences=new ArrayList<Map<String,Object>>();
        for(var row:jdbc.queryForList("SELECT unique_id,financial_loans_hex,uefa_coefficient_hex,uefa_rule_version FROM club_records")) {
            long uid=((Number)row.get("UNIQUE_ID")).longValue();String loan=(String)row.get("FINANCIAL_LOANS_HEX");String previous=oldLoans.get(uid);
            if(previous==null)assertThat(loan).isNull();else {
                assertThat(loan).hasSize(previous.length());byte[] before=HexFormat.of().parseHex(previous),after=HexFormat.of().parseHex(loan);
                if(after.length>0)loanOwners++;loanRecords+=after.length/28;
                for(int i=0;i<after.length;i++)if(i%28==0x19){if(before[i]!=after[i])unknown19Changes++;}
                    else if(i%28==0x1B){if(before[i]!=after[i])unknown1bChanges++;}
                    else assertThat(after[i]).as("UID%s loan byte%s",uid,i).isEqualTo(before[i]);
            }
            String uefa=(String)row.get("UEFA_COEFFICIENT_HEX"),priorUefa=oldUefa.get(uid);
            if(priorUefa==null)assertThat(uefa).isNull();else {
                uefaProfiles++;assertThat(((Number)row.get("UEFA_RULE_VERSION")).intValue()).isEqualTo(1639);
                var layout=ClubUefaCoefficientLayout.current();byte[] before=HexFormat.of().parseHex(priorUefa),after=HexFormat.of().parseHex(uefa);
                assertThat(layout.decode(after,1639)).isEqualTo(layout.decode(before,1639));
                for(int i=0;i<before.length;i++)if(before[i]!=after[i])uefaRawDifferences.add(Map.of("uid",uid,"offsetHex",Integer.toHexString(i),"old",Byte.toUnsignedInt(before[i]),"new",Byte.toUnsignedInt(after[i])));
            }
        }
        assertThat(loanRecords).isEqualTo(477);assertThat(loanOwners).isEqualTo(228);assertThat(uefaProfiles).isEqualTo(1903);
        byte[] probe;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/financial_loan_restart600_live_20261027.json"))){probe=in.readNBytes(65537);}assertThat(probe.length).isLessThanOrEqualTo(65536);
        var observation=new tools.jackson.databind.json.JsonMapper().readTree(probe);assertThat(observation.path("ok").asBoolean()).isTrue();var nativeLoan=observation.path("returnValues").get(0);
        assertThat(nativeLoan.path("pid").asInt()).isEqualTo(22268);assertThat(nativeLoan.path("uid").asLong()).isEqualTo(600);assertThat(nativeLoan.path("guardsUnchanged").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT financial_loans_hex FROM club_records WHERE unique_id=600",String.class)).isEqualTo(nativeLoan.path("records").get(0).path("rawHex").asText());
        var report=new LinkedHashMap<String,Object>();report.put("canonicalClubs",clubs);report.put("profiles",owners);report.put("members",members);report.put("allPayloadsMatch",true);report.put("preserved",preserved);
        report.put("loanRecords",loanRecords);report.put("loanOwners",loanOwners);report.put("allKnownLoanBytesUnchanged",true);report.put("unknownLoan19ChangesSincePreviousProcess",unknown19Changes);report.put("unknownLoan1bChangesSincePreviousProcess",unknown1bChanges);report.put("uid600RawLoanMatchesFreshIndependentRam",true);report.put("uefaProfilesDecodedEqual",uefaProfiles);report.put("uefaRawByteDifferencesSincePreviousProcess",uefaRawDifferences);
        report.put("snapshot",jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='snapshot_id'",String.class));report("fm26.financial.competitions.database.report",report);
    }
    private static void report(String property,Map<String,Object> report)throws Exception {
        String dest=System.getProperty(property);assertThat(dest).isNotBlank();Files.writeString(Path.of(dest),new tools.jackson.databind.json.JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report),StandardOpenOption.CREATE_NEW);
    }
}
