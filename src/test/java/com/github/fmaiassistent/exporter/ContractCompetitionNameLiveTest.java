package com.github.fmaiassistent.exporter;
import com.github.fmaiassistent.memory.*;
import com.github.fmaiassistent.linux.FmOffsets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("fm26-live")
@EnabledIfSystemProperty(named="fm26.competition.bonuses.live",matches="true")
class ContractCompetitionNameLiveTest {
    @Test void productionReadsAll30NamesAndBothNativeOwnerRowsWithoutTargetCalls()throws Exception {
        int pid=Integer.getInteger("fm26.live.pid",-1);assertThat(pid).isEqualTo(22268);
        assertThat(ContractExporter.supportedModule(Path.of(System.getProperty(ContractExporter.MODULE_PROPERTY)))).isTrue();
        var f=ContractCaptureFixture.competitionBonus(0);var row=ContractCompetitionNameCaptureTest.owner(f,true);
        var selected=new ArrayList<Object>();long started=System.nanoTime();
        try(var reader=ProcessReaders.open(pid)) {
            long base=FmOffsets.findGamePluginBase(reader);
            new ContractCompetitionNameExporter(reader,base,CompetitionNameLayout.current()).captureRows(List.of(row));
            var captured=AwardNameCaptureTest.json("competition_bonus_references_checkpoint56_live_20261027.json").get("returnValues").get(0);
            for(var owner:captured.get("owners")) {
                var contracts=new ContractExporter(reader,base,ContractRecordLayout.current()).read(Long.parseUnsignedLong(owner.get("person").asString(),16),owner.get("uid").asLong());
                var term=contracts.stream().flatMap(c->c.terms().stream()).filter(t->"FULL_80".equals(t.group())).findFirst().orElseThrow();
                assertThat(term.rawHex()).isEqualToIgnoringCase(owner.get("rowsRaw").asString());
                selected.add(Map.of("uid",owner.get("uid").asLong(),"raw",term.rawHex(),"references",term.competitionReferences()));
            }
        }
        var actual=new TreeMap<Long,com.github.fmaiassistent.domain.NativeCompetitionNameSnapshot>();for(var n:ContractCompetitionNameCaptureTest.names(row))actual.put(n.uniqueId(),n);
        assertThat(actual).hasSize(30);
        for(var expected:AwardNameCaptureTest.json("competition_bonus_references_checkpoint56_live_20261027.json").get("returnValues").get(0).get("names")) {
            var n=actual.get(expected.get("uid").asLong());assertThat(n.recordIndex()).isEqualTo(expected.get("index").asInt());
            assertThat(n.nameHex()).isEqualToIgnoringCase(expected.get("names").get(0).get("raw").asString());
            assertThat(n.shortNameHex()).isEqualToIgnoringCase(expected.get("names").get(1).get("raw").asString());
        }
        String output=System.getProperty("fm26.competition.bonuses.live.report");if(output!=null)Files.write(Path.of(output),
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(Map.of("pid",pid,"names",actual.values(),"selectedOwners",selected,
                        "elapsedMs",(System.nanoTime()-started)/1_000_000,"allNamesMatchIndependentCapture",true)),StandardOpenOption.CREATE_NEW);
    }
}
