package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.*;
import com.github.fmaiassistent.linux.FmOffsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** One-shot passive fixture acquisition, separate from deterministic fixture replay. */
@EnabledIfSystemProperty(named="fm27.captureFixtures",matches="true")
class Fm27ContractCaptureLiveTest {
    @Test
    @SuppressWarnings("unchecked")
    void retainBoundedProductionReaderInputsForEachNativeContractTypeAndFutureEmployment() throws Exception {
        var mapper=JsonMapper.builder().build();
        Map<String,Object> inventory;
        try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm27/contracts_inventory.json"))){
            byte[] raw=in.readNBytes(1_000_001);assertThat(raw.length).isLessThanOrEqualTo(1_000_000);
            inventory=mapper.readValue(raw,Map.class);
        }
        Map<String,List<Map<String,Object>>> samples=(Map<String,List<Map<String,Object>>>)inventory.get("samples");
        var cases=new ArrayList<Map<String,Object>>();
        int pid=Integer.getInteger("fm27.live.pid",-1);assertThat(pid).isPositive();
        try(var source=ProcessReaders.open(pid)){
            long base=FmOffsets.findGamePluginBase(source);
            for(var entry:samples.entrySet()){
                var sample=entry.getValue().stream().filter(s->Boolean.TRUE.equals(s.get("competitionBonus"))).findFirst().orElse(entry.getValue().getFirst());
                long person=Long.parseLong(((String)sample.get("person")).substring(2),16),uid=((Number)sample.get("uid")).longValue();
                var reader=new RecordingReader(source);
                var actual=new ContractExporter(reader,base,ContractRecordLayout.current()).read(person,uid);
                assertThat(actual).isNotEmpty();
                var item=new LinkedHashMap<String,Object>();item.put("nativeType",entry.getKey());item.put("base",base);item.put("person",person);item.put("uid",uid);
                item.put("contracts",actual);item.put("memory",reader.memory);cases.add(item);
            }
            Map<String,Object> future;
            try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm27/future_inventory.json"))){
                byte[] raw=in.readNBytes(1_000_001);assertThat(raw.length).isLessThanOrEqualTo(1_000_000);future=mapper.readValue(raw,Map.class);
            }
            var sample=((List<Map<String,Object>>)future.get("employment")).getFirst();
            long person=Long.parseLong(((String)sample.get("person")).substring(2),16),uid=((Number)sample.get("uid")).longValue();
            int offer=((Number)sample.get("offerIndex")).intValue();var reader=new RecordingReader(source);
            var actual=new ContractExporter(reader,base,ContractRecordLayout.current()).readFutureEmployment(person,uid,offer).orElseThrow();
            assertThat(actual.wageRaw()).isEqualTo(((Number)sample.get("wage")).intValue());
            var item=new LinkedHashMap<String,Object>();item.put("nativeType","FUTURE");item.put("base",base);item.put("person",person);item.put("uid",uid);item.put("offer",offer);
            item.put("future",actual);item.put("memory",reader.memory);cases.add(item);
        }
        var target=Path.of("src/test/resources/fm27/contracts-captured.json");Files.createDirectories(target.getParent());
        byte[] json=mapper.writeValueAsBytes(cases);assertThat(json.length).isLessThan(2_000_000);Files.write(target,json);
    }

    private static final class RecordingReader implements ProcessMemoryReader {
        private final ProcessMemoryReader delegate;private long bytes;
        private final Map<String,String> memory=new LinkedHashMap<>();
        RecordingReader(ProcessMemoryReader delegate){this.delegate=delegate;}
        public int pid(){return delegate.pid();}public Platform platform(){return delegate.platform();}
        public List<MemoryRegion> maps()throws IOException{return delegate.maps();}public void close(){}
        public byte[] readBytes(long p,int n)throws IOException{
            if(n>65536||(bytes+=n)>2_000_000)throw new IOException("Fixture capture budget exceeded");
            byte[] raw=delegate.readBytes(p,n);String key=p+":"+n,hex=HexFormat.of().formatHex(raw),old=memory.putIfAbsent(key,hex);
            if(old!=null&&!old.equals(hex))throw new IOException("Fixture input changed");return raw;
        }
    }
}
