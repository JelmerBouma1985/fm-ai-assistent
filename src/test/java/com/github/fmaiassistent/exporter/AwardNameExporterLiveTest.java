package com.github.fmaiassistent.exporter;
import com.github.fmaiassistent.memory.*;
import com.github.fmaiassistent.linux.FmOffsets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("fm26-live")
@EnabledIfSystemProperty(named="fm26.awards.live",matches="true")
class AwardNameExporterLiveTest {
    @Test void productionReadMatchesAll43IndependentAwardNamesWithoutRefreshOrTargetCalls()throws Exception {
        int pid=Integer.getInteger("fm26.live.pid",-1);assertThat(pid).isEqualTo(22268);
        String module=System.getProperty(ContractExporter.MODULE_PROPERTY);assertThat(module).isNotBlank();assertThat(ContractExporter.supportedModule(Path.of(module))).isTrue();
        var rows=AwardNameCaptureTest.ownerRows();long start=System.nanoTime();
        try(var reader=ProcessReaders.open(pid)){new AwardNameExporter(reader,FmOffsets.findGamePluginBase(reader),AwardNameLayout.current()).captureRows(rows);}
        long elapsed=(System.nanoTime()-start)/1_000_000;
        var actual=new TreeMap<Long,com.github.fmaiassistent.domain.NativeAwardNameSnapshot>();for(var r:rows)for(var n:AwardNameCaptureTest.names(r))actual.put(n.uniqueId(),n);
        assertThat(actual).hasSize(43);
        for(var a:AwardNameCaptureTest.json("referenced_award_names_checkpoint54_verified_20261027.json").get("rows")){
            var n=actual.get(a.get("uid").asLong());assertThat(n.recordIndex()).isEqualTo(a.get("index").asInt());
            assertThat(n.nameHex()).isEqualToIgnoringCase(a.get("names").get(0).get("raw").asString());
            assertThat(n.shortNameHex()).isEqualToIgnoringCase(a.get("names").get(1).get("raw").asString());
        }
        String out=System.getProperty("fm26.awards.live.report");if(out!=null)Files.write(Path.of(out),tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(Map.of(
                "pid",pid,"names",43,"storedReferences",1154,"elapsedMs",elapsed,"allNamesMatchIndependentCapture",true,"scope","Production bounded AWARD reads using same-process candidate references, with native UID/index/registry/type proof; no refresh")),StandardOpenOption.CREATE_NEW);
    }
}
