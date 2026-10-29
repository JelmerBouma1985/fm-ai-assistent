package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.domain.FutureTransferSnapshot;
import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.memory.ProcessReaders;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("fm26-live")
@EnabledIfSystemProperty(named="fm26.live.futureTransfer",matches="true")
class FutureTransferExporterLiveTest {
    @Test void batchSelectionMatchesIndependentCurrentCePeopleAndKeepsCurrentContractsSeparate()throws Exception {
        int pid=Integer.getInteger("fm26.live.pid",-1);assertThat(pid).isPositive();
        var mapper=JsonMapper.builder().build();byte[] raw;
        try(var in=Files.newInputStream(Path.of(System.getProperty("fm26.live.futureEmployment.receipt")))) {
            raw=in.readNBytes(4_194_305);assertThat(raw.length).isLessThanOrEqualTo(4_194_304);
        }
        var root=mapper.readTree(raw).get("returnValues").get(0);assertThat(root.get("pid").asInt()).isEqualTo(pid);
        var rows=new ArrayList<Map<String,Object>>();
        for(var ce:root.get("rows")) {
            var row=new HashMap<String,Object>();row.put("record",Long.parseUnsignedLong(ce.get("person").get("address").asString(),16));
            row.put("unique_id",ce.get("uid").asLong());row.put("transfer_agreed",false);row.put("index",ce.get("person").get("index").asInt());rows.add(row);
        }
        long started=System.nanoTime();
        try(var reader=ProcessReaders.open(pid)) {ContractExporter.capture(reader,FmOffsets.findGamePluginBase(reader),rows);}
        var reportRows=new ArrayList<Map<String,Object>>();int total=0;
        for(int i=0;i<rows.size();i++) {
            var ce=root.get("rows").get(i);var row=rows.get(i);
            assertThat(row.get("_contract_profile")).isEqualTo(ContractExporter.MODULE_SHA256);
            assertThat(row.get("transfer_agreed")).isEqualTo(true);
            @SuppressWarnings("unchecked") var futures=(List<FutureTransferSnapshot>)row.get("_future_contracts");
            var future=futures.stream().filter(f->f.employment().offerIndex()==ce.get("slot").asInt()).findFirst().orElseThrow();
            for(var record:ce.get("records"))if(record.get("offerIndex").asInt()==ce.get("slot").asInt()) {
                assertThat(future.employment().contract().coreHex()).isEqualTo(record.get("contractCore").asString());
                assertThat(future.employment().contract().teamOwnerUid()).isEqualTo(record.get("club").get("uid").asLong());
            }
            total+=future.employment().contract().terms().size();
            var item=new LinkedHashMap<String,Object>();item.put("uid",row.get("unique_id"));item.put("offer",future.employment().offerIndex());
            item.put("club",row.get("future_transfer_club"));item.put("date",row.get("future_transfer_date"));item.put("newEnd",row.get("future_transfer_contract_end_date"));
            item.put("profile",row.get("_future_transfer_profile"));item.put("terms",future.employment().contract().terms().size());reportRows.add(item);
        }
        PlayerExporter.applyGameDate(rows,java.time.LocalDate.of(2036,5,20));
        assertThat(rows).allMatch(r->Boolean.TRUE.equals(r.get("transfer_agreed")));
        var report=new LinkedHashMap<String,Object>();report.put("pid",pid);report.put("rows",reportRows);report.put("terms",total);
        report.put("elapsedMs",(System.nanoTime()-started)/1_000_000);report.put("selectedPeople",rows.size());report.put("limitation","Selected current CE people, not full population; computed date branch unavailable, unknown offers reported partial");
        Files.write(Path.of(System.getProperty("fm26.live.futureTransfer.report")),mapper.writeValueAsBytes(report),StandardOpenOption.CREATE_NEW);
    }
}
