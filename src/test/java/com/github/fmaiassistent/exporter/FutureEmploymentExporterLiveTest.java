package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.memory.ContractRecordLayout;
import com.github.fmaiassistent.memory.ProcessReaders;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in, passive comparison with a separate, freshly collected Cheat Engine receipt. */
@Tag("fm26-live")
@EnabledIfSystemProperty(named="fm26.live.futureEmployment",matches="true")
class FutureEmploymentExporterLiveTest {
    @Test void currentProcessMatchesIndependentCeRecordSelectionAndRawContractTerms() throws Exception {
        int pid=Integer.getInteger("fm26.live.pid",-1);assertThat(pid).isPositive();
        var mapper=JsonMapper.builder().build();
        Path receipt=Path.of(System.getProperty("fm26.live.futureEmployment.receipt"));
        byte[] source;
        try(var in=Files.newInputStream(receipt)) {
            source=in.readNBytes(4_194_305);assertThat(source.length).isLessThanOrEqualTo(4_194_304);
        }
        var capture=mapper.readTree(source);assertThat(capture.get("ok").asBoolean()).isTrue();
        var root=capture.get("returnValues").get(0);
        assertThat(root.get("pid").asInt()).isEqualTo(pid);assertThat(root.get("guardsUnchanged").asBoolean()).isTrue();
        Path module=Path.of(System.getProperty(ContractExporter.MODULE_PROPERTY));
        assertThat(ContractExporter.supportedModule(module)).isTrue();
        var rows=new ArrayList<Map<String,Object>>();int termCount=0;
        try(var reader=ProcessReaders.open(pid)) {
            long base=FmOffsets.findGamePluginBase(reader);
            assertThat(base).isEqualTo(Long.parseUnsignedLong(root.get("moduleBase").asString(),16));
            var decoder=new ContractExporter(reader,base,ContractRecordLayout.current());
            for(var row:root.get("rows")) {
                long person=Long.parseUnsignedLong(row.get("person").get("address").asString(),16);
                int offer=row.get("slot").asInt();long uid=row.get("uid").asLong();
                var actual=decoder.readFutureEmployment(person,uid,offer).orElseThrow();
                boolean matched=false;
                for(var record:row.get("records"))if(record.get("offerIndex").asInt()==offer) {
                    matched=true;
                    byte[] raw=HexFormat.of().parseHex(record.get("contractCore").asString());
                    var core=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                    assertThat(actual.recordHex()).isEqualTo(record.get("rawPrefix").asString());
                    assertThat(actual.contract().coreHex()).isEqualTo(record.get("contractCore").asString());
                    assertThat(actual.wageRaw()).isEqualTo(core.getInt(0x20));
                    assertThat(actual.state()).isEqualTo(record.get("state").asInt());
                    assertThat(actual.contract().teamOwnerUid()).isEqualTo(record.get("club").get("uid").asLong());
                    for(var collection:record.get("collections")) {
                        String group=collection.get("group").asString();
                        String actualRaw=actual.contract().terms().stream().filter(t->t.group().equals(group))
                                .map(t->t.rawHex()).collect(java.util.stream.Collectors.joining());
                        assertThat(actualRaw).isEqualTo(collection.get("rawHex").asString());
                    }
                    assertThat(actual.endDateUsesGameDateFlag()).isFalse(); // Branch not present in these real captures.
                    assertThat(actual.endDate().getYear()).isEqualTo(Short.toUnsignedInt(core.getShort(0x4A)));
                    assertThat(actual.endDate().getDayOfYear()).isEqualTo(Short.toUnsignedInt(core.getShort(0x48))&511);
                    break;
                }
                assertThat(matched).isTrue();termCount+=actual.contract().terms().size();
                var result=new LinkedHashMap<String,Object>();result.put("uid",uid);result.put("offerIndex",offer);
                result.put("clubUid",actual.contract().teamOwnerUid());result.put("wageRaw",actual.wageRaw());
                result.put("endDate",actual.endDate().toString());result.put("terms",actual.contract().terms().size());
                rows.add(result);
            }
        }
        assertThat(rows).isNotEmpty();
        var report=new LinkedHashMap<String,Object>();report.put("pid",pid);report.put("moduleSha256",ContractExporter.MODULE_SHA256);
        report.put("rows",rows);report.put("terms",termCount);report.put("ceRawBytesMatch",true);
        report.put("limitation","Selected future record decoder only; no batch export, persistence, screen or complete offer-membership/status pipeline proof");
        Files.write(Path.of(System.getProperty("fm26.live.futureEmployment.report")),mapper.writeValueAsBytes(report),StandardOpenOption.CREATE_NEW);
    }
}
