package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.domain.ContractSnapshot;
import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.memory.ProcessReaders;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.ContractRecordLayout;
import java.io.IOException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly enabled, read-only comparison of the production decoder with independent CE observations. */
@Tag("fm26-live")
@EnabledIfSystemProperty(named = "fm26.live", matches = "true")
class ContractExporterLiveTest {
    @Test
    @EnabledIfSystemProperty(named = "fm26.live.contract.all", matches = "true")
    void completeNativePlayerProjectionFitsContractReaderBounds() throws Exception {
        int pid = Integer.getInteger("fm26.live.pid", -1);
        assertThat(pid).isPositive();
        try (var reader = ProcessReaders.open(pid)) {
            long base = FmOffsets.findGamePluginBase(reader);
            // Independent native registry oracle; these are not production extractor offsets.
            long header = base + 0x4E49578 + 0x100;
            byte[] initial = reader.readBytes(header, 24);
            var b = java.nio.ByteBuffer.wrap(initial).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            long begin = b.getLong(), end = b.getLong();
            assertThat(end - begin).isBetween(8L, 800000L);
            assertThat((end - begin) % 8).isZero();
            byte[] pointers = reader.readBytes(begin, Math.toIntExact(end - begin));
            var p = java.nio.ByteBuffer.wrap(pointers).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            List<Map<String,Object>> rows = new ArrayList<>();
            for (int i=0; i<pointers.length/8; i++) {
                long primary = p.getLong(i*8), vtable = reader.readU64(primary);
                assertThat(vtable-base).isIn(0x4509828L, 0x4785308L);
                long person = primary + (vtable-base == 0x4509828L ? 0x288 : 0x380);
                var row = new LinkedHashMap<String,Object>();
                row.put("record", "0x"+Long.toHexString(person)); row.put("unique_id",reader.readU32(person+12));
                rows.add(row);
            }
            var measured = new MeasuredReader(reader,base);
            long started = System.nanoTime();
            ContractExporter.capture(measured, base, rows);
            long elapsed = (System.nanoTime()-started)/1_000_000;
            Map<String,Long> kinds = new java.util.TreeMap<>();
            Map<String,Long> groups = new java.util.TreeMap<>();
            Map<String,Long> ownerKinds = new java.util.TreeMap<>();
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            var digestMapper=JsonMapper.builder().build();
            long termCount=0, referenceCount=0, ownersResolved=0;
            for (var row : rows) {
                assertThat(row.get("_contract_profile")).isEqualTo(ContractExporter.MODULE_SHA256);
                @SuppressWarnings("unchecked") var contracts=(List<ContractSnapshot>)row.get("_contracts");
                for (var c : contracts) {
                    digest.update(digestMapper.writeValueAsBytes(c));digest.update((byte)'\n');
                    kinds.merge(c.kind(),1L,Long::sum); termCount+=c.terms().size();
                    if(c.teamOwnerKind()!=null)ownerKinds.merge(c.teamOwnerKind(),1L,Long::sum);
                    if(c.teamOwnerUid()!=null)ownersResolved++;
                    for (var term : c.terms()) {
                        groups.merge(term.group(),1L,Long::sum);
                        if (term.referenceUid() != null) referenceCount++;
                    }
                }
            }
            assertThat(reader.readBytes(header,24)).isEqualTo(initial);
            String output=System.getProperty("fm26.live.contract.all.report");
            var report=new LinkedHashMap<String,Object>();
            report.put("pid",pid);report.put("moduleBase",Long.toHexString(base));report.put("players",rows.size());
            report.put("contractsByKind",kinds);report.put("terms",termCount);report.put("termsByGroup",groups);
            report.put("elapsedMs",elapsed);report.put("headersUnchanged",true);report.put("resolvedReferences",referenceCount);
            report.put("teamOwnersByKind",ownerKinds);report.put("resolvedTeamOwners",ownersResolved);
            report.put("readCalls",measured.calls);report.put("readBytes",measured.bytes);
            report.put("readsBySize",measured.sizes);report.put("trackedRegistryReads",measured.trackedReads);
            report.put("contractDtoSha256",java.util.HexFormat.of().formatHex(digest.digest()));
            report.put("limitation","Bounded production contract capture over native player projection; summary only, no full memory dump or native atomicity/UI proof");
            if(output!=null)Files.write(Path.of(output),JsonMapper.builder().build().writeValueAsBytes(report),java.nio.file.StandardOpenOption.CREATE_NEW);
        }
    }
    /** Count native-facing reads without replacing optimized delegate readInto with allocating defaults. */
    private static final class MeasuredReader implements ProcessMemoryReader {
        private final ProcessMemoryReader delegate;
        private final Map<Long,String> tracked=new java.util.HashMap<>();
        private final Map<String,Long> trackedReads=new java.util.TreeMap<>();
        private final Map<Integer,Long> sizes=new java.util.TreeMap<>();
        private long calls,bytes;
        MeasuredReader(ProcessMemoryReader delegate,long base) throws IOException {
            this.delegate=delegate;
            var layout=ContractRecordLayout.current();
            int[] roots={layout.links().teamsRegistry(),layout.teamOwner().clubsRegistry(),layout.teamOwner().nationalContainersRegistry(),layout.links().competitionsRegistry(),layout.award().registry()};
            String[] names={"TEAM","CLUB","NATIONAL_TEAM_CONTAINER","COMP","AWARD"};
            for(int i=0;i<roots.length;i++) {
                long descriptor=delegate.readU64(base+roots[i]);long header=delegate.readU64(descriptor+layout.links().registryHeader());
                tracked.put(base+roots[i],names[i]+".root");tracked.put(descriptor+layout.links().registryHeader(),names[i]+".headerPointer");
                tracked.put(header,names[i]+".begin");tracked.put(header+8,names[i]+".end");
            }
        }
        private void count(long address,int size){calls++;bytes+=size;sizes.merge(size,1L,Long::sum);String label=tracked.get(address);if(label!=null)trackedReads.merge(label,1L,Long::sum);}
        @Override public int pid(){return delegate.pid();}
        @Override public Platform platform(){return delegate.platform();}
        @Override public List<MemoryRegion> maps()throws IOException{return delegate.maps();}
        @Override public byte[] readBytes(long address,int size)throws IOException{count(address,size);return delegate.readBytes(address,size);}
        @Override public void readInto(long address,byte[] target,int offset,int length)throws IOException{count(address,length);delegate.readInto(address,target,offset,length);}
        @Override public void close(){} // The enclosing live test owns the actual reader.
    }
    @Test
    void allEightIndependentConditionalLoanSamplesReachProductionDecoder() throws Exception {
        int pid = Integer.getInteger("fm26.live.pid", -1);
        assertThat(pid).isPositive();
        byte[] bytes;
        try (var input = Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/conditional_loan_samples_live_20261023.json"))) {
            bytes = input.readNBytes(1_048_577);
        }
        assertThat(bytes.length).isLessThanOrEqualTo(1_048_576);
        var expected = JsonMapper.builder().build().readTree(bytes).get("returnValues").get(0);
        List<Map<String,Object>> rows = new ArrayList<>();
        try (var reader = ProcessReaders.open(pid)) {
            long base = FmOffsets.findGamePluginBase(reader);
            var people = FmOffsets.peopleBounds(reader, 0x238BDD, base);
            for (var sample : expected.get("selected")) {
                int index = sample.get("index").asInt();
                assertThat(index).isBetween(0, Math.toIntExact(people.count()-1));
                long person = reader.readU64(people.start() + (long) index * Long.BYTES);
                assertThat(reader.readU32(person + 12)).isEqualTo(sample.get("uid").asLong());
                var row = new LinkedHashMap<String,Object>();
                row.put("record", "0x"+Long.toHexString(person)); row.put("unique_id", sample.get("uid").asLong());
                rows.add(row);
            }
            ContractExporter.capture(reader, base, rows);
            for (int i=0; i<rows.size(); i++) {
                var row = rows.get(i); var sample = expected.get("selected").get(i);
                assertThat(row.get("_contract_profile")).isEqualTo(ContractExporter.MODULE_SHA256);
                @SuppressWarnings("unchecked") var contracts = (List<ContractSnapshot>) row.get("_contracts");
                var loan = contracts.stream().filter(c -> c.kind().equals("LOAN") && c.slot()==0).findFirst().orElseThrow();
                assertThat(loan.ownerUid()).isEqualTo(sample.get("uid").asLong());
                assertThat(loan.threshold()).isEqualTo(sample.get("threshold").asInt());
                assertThat(loan.played()).isEqualTo(sample.get("played").asInt());
                assertThat(loan.competitionIndex()).isEqualTo(sample.get("competition").get("index").asInt());
                if (!sample.get("competition").get("absent").asBoolean())
                    assertThat(loan.competitionUid()).isEqualTo(sample.get("competition").get("uid").asLong());
                if (sample.get("uid").asLong() == 49048469L) {
                    var awards = contracts.stream().filter(c -> c.kind().equals("FULL"))
                            .flatMap(c -> c.terms().stream()).filter(t -> t.group().equals("FULL_98")).toList();
                    assertThat(awards.stream().map(ContractSnapshot.Term::referenceUid).toList()).containsExactly(150936L,150937L);
                    assertThat(awards).allMatch(t -> "AWARD".equals(t.referenceKind()));
                }
                if(sample.get("uid").asLong()==43390774L) {
                    var full=contracts.stream().filter(c->c.kind().equals("FULL")).findFirst().orElseThrow();
                    assertThat(full.teamOwnerKind()).isEqualTo("CLUB");assertThat(full.teamOwnerUid()).isEqualTo(1123L);
                    assertThat(loan.teamOwnerKind()).isEqualTo("CLUB");assertThat(loan.teamOwnerUid()).isEqualTo(1149L);
                }
            }
            String output = System.getProperty("fm26.live.contract.report");
            if (output != null) Files.write(Path.of(output), JsonMapper.builder().build().writeValueAsBytes(
                    Map.of("pid",pid,"moduleBase",Long.toHexString(base),"comparedLoans",rows.size(),"rows",rows,
                            "limitation","Selected production decoder comparison; not full export, UI, save-generation or database verification")),
                    java.nio.file.StandardOpenOption.CREATE_NEW);
        }
    }
}
