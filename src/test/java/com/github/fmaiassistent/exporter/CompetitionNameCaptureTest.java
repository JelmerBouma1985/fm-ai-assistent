package com.github.fmaiassistent.exporter;
import com.github.fmaiassistent.domain.NativeCompetitionNameSnapshot;
import com.github.fmaiassistent.memory.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Names and identity evidence come from the independent60-key live capture and decoder. */
public class CompetitionNameCaptureTest {
    static final CompetitionNameLayout L=CompetitionNameLayout.current();
    static final long BASE=ClubFinancialLoanCaptureTest.BASE;
    static tools.jackson.databind.JsonNode json(String name)throws Exception {
        byte[] bytes;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence",name))){bytes=in.readNBytes(1048577);}assertThat(bytes.length).isLessThanOrEqualTo(1048576);
        return new tools.jackson.databind.json.JsonMapper().readTree(bytes);
    }
    public static List<NativeCompetitionNameSnapshot> observedNames()throws Exception {
        var decoded=new HashMap<Long,tools.jackson.databind.JsonNode>();for(var n:json("financial_competition_names_verified_20261027.json").path("rows"))decoded.put(n.path("uid").asLong(),n);
        var out=new ArrayList<NativeCompetitionNameSnapshot>();for(var n:json("financial_competition_names_live_20261027.json").path("returnValues").get(0).path("rows")) {
            var d=decoded.get(n.path("uid").asLong());out.add(new NativeCompetitionNameSnapshot(n.path("uid").asLong(),n.path("index").asInt(),hex(n.path("address").asText()),n.path("prefix80Hex").asText().substring(0,32),
                    stringHex(n.path("field40")),stringHex(n.path("field48")),d.path("longName").isNull()?null:d.path("longName").asText(),d.path("shortName").isNull()?null:d.path("shortName").asText()));
        }assertThat(out).hasSize(60);return out;
    }
    static String stringHex(tools.jackson.databind.JsonNode n){return n.path("state").asText().equals("null")?null:n.path("lengthHex").asText()+n.path("rawHex").asText();}
    static ClubUefaCoefficientCaptureTest.Blocks fixture()throws Exception {
        var f=ClubFinancialCompetitionCaptureTest.fixture();var addresses=new HashMap<Long,Long>();ClubFinancialCompetitionCaptureTest.keys().forEach((a,u)->addresses.put(u,a));
        for(var n:json("financial_competition_names_live_20261027.json").path("returnValues").get(0).path("rows")) {
            long address=addresses.get(n.path("uid").asLong());
            for(var entry:Map.of(0x40,n.path("field40"),0x48,n.path("field48")).entrySet()) {
                var text=entry.getValue();long pointer=hex(text.path("pointer").asText());f.put(address+entry.getKey(),ClubFinancialLoanCaptureTest.longs(pointer));
                if(pointer!=0)f.put(pointer,HexFormat.of().parseHex(stringHex(text)));
            }
        }return f;
    }
    static ClubIdentitySnapshot capture(ClubUefaCoefficientCaptureTest.Blocks f)throws Exception {
        return new ClubIdentitySnapshot(f,BASE,ClubIdentityLayout.current(),null,null,ClubFinancialLedgerLayout.current(),ClubFinancialCompetitionLayout.current(),L);
    }
    public static ClubExporter.ExportResult capturedResult()throws Exception {
        var capture=capture(fixture());capture.verify();return new ClubExporter.ExportResult(List.of(),capture.records(),ClubFinancialLedgerLayout.MODULE_SHA256,capture.competitionNames());
    }
    @Test void all60ReferencesKeepBothRawNamesIncluding20NullPointersAndEveryFinancialMember()throws Exception {
        var f=fixture();var capture=capture(f);var expected=observedNames().stream().collect(java.util.stream.Collectors.toMap(NativeCompetitionNameSnapshot::uniqueId,n->n));
        assertThat(capture.competitionNames()).hasSize(60);int nullNames=0;
        for(var r:capture.competitionNames()){var e=expected.get(r.uniqueId());assertThat(r.recordIndex()).isEqualTo(e.recordIndex());assertThat(r.nameHex()).isEqualTo(e.nameHex());assertThat(r.shortNameHex()).isEqualTo(e.shortNameHex());assertThat(r.name()).isEqualTo(e.name());assertThat(r.shortName()).isEqualTo(e.shortName());if(r.nameHex()==null){nullNames++;assertThat(r.shortNameHex()).isNull();}}
        assertThat(nullNames).isEqualTo(20);var vectors=ClubFinancialCompetitionCaptureTest.expectedPayloads();for(var club:capture.records())assertThat(club.financialCompetitionsHex()).isEqualTo(vectors.get(club.uniqueId()));capture.verify();
    }
    @Test void allSixShiftedLayoutsFailTheSameIndependentFixtureBeforeReading()throws Exception {
        var f=fixture();var parts=CompetitionNameLayout.class.getRecordComponents();var types=Arrays.stream(parts).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);var constructor=CompetitionNameLayout.class.getDeclaredConstructor(types);
        for(int i=0;i<parts.length;i++){Object[] args=new Object[parts.length];for(int n=0;n<args.length;n++)args[n]=parts[n].getAccessor().invoke(L);args[i]=(Integer)args[i]+1;var shifted=constructor.newInstance(args);
            assertThatThrownBy(()->new ClubIdentitySnapshot(f,BASE,ClubIdentityLayout.current(),null,null,ClubFinancialLedgerLayout.current(),ClubFinancialCompetitionLayout.current(),shifted)).as(parts[i].getName()).isInstanceOf(IOException.class).hasMessageContaining("layout anchor");}
    }
    @Test void namePointerLengthPayloadAndNullAbsenceChangesAbortFinalPublication()throws Exception {
        var f=fixture();var capture=capture(f);var n=json("financial_competition_names_live_20261027.json").path("returnValues").get(0).path("rows");
        var one=java.util.stream.StreamSupport.stream(n.spliterator(),false).filter(r->r.path("uid").asLong()==29).findFirst().orElseThrow();
        long pointer=hex(one.path("field40").path("pointer").asText());long comp=ClubFinancialCompetitionCaptureTest.keys().entrySet().stream().filter(e->e.getValue()==29L).findFirst().orElseThrow().getKey();
        var nullRow=capture.competitionNames().stream().filter(r->r.nameHex()==null).findFirst().orElseThrow();
        for(long a:new long[]{comp+0x40,comp+0x48,pointer,pointer+4,nullRow.sourceAddress()+0x40}) {
            var entry=f.blocks.floorEntry(a);byte[] prior=entry.getValue(),changed=prior.clone();changed[Math.toIntExact(a-entry.getKey())]^=1;f.put(entry.getKey(),changed);
            assertThatThrownBy(capture::verify).isInstanceOf(IOException.class).hasMessageContaining("changed");f.put(entry.getKey(),prior);
        }capture.verify();
    }
    static long hex(String s){return Long.parseUnsignedLong(s,16);}
}
