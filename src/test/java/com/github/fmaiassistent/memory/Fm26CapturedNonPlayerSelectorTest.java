package com.github.fmaiassistent.memory;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Captured code/table proof only; no native invocation, UI-path claim or production formula change. */
class Fm26CapturedNonPlayerSelectorTest {
    private static final Path ROOT=Path.of("docs/reverse-engineering/fm26/evidence");
    @Test
    @org.junit.jupiter.api.Tag("fm26-live")
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="fm26.staff.normalization.live",matches="true")
    void captureExistingStaffZerosAsCandidatesForAnIndependentUiCheck()throws Exception {
        String url=System.getProperty("fm26.delivery.database.url");
        assertThat(url).startsWith("jdbc:h2:file:").contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE");
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url,"sa",""));
        var rows=jdbc.queryForList("SELECT unique_id,name,record_address,club,job,fitness,defending,attacking FROM staff "
                +"WHERE (fitness=0 OR defending=0 OR attacking=0) AND job_id IN (2,16,20,26,34,52,54,64) "
                +"ORDER BY CASE WHEN club LIKE '%Heerenveen%' THEN 0 ELSE 1 END,unique_id FETCH FIRST 12 ROWS ONLY");
        var samples=new ArrayList<Map<String,Object>>();int pid=Integer.getInteger("fm26.live.pid",-1);assertThat(pid).isPositive();
        try(var memory=ProcessReaders.open(pid)) {
            long base=com.github.fmaiassistent.linux.FmOffsets.findGamePluginBase(memory);
            long descriptor=memory.readU64(base+0x4E495E0),header=memory.readU64(descriptor+0x80);
            long first=memory.readU64(header),end=memory.readU64(header+8);
            for(var row:rows) {
                long person=Long.parseUnsignedLong(((String)row.get("RECORD_ADDRESS")).substring(2),16);
                byte[] prefix=memory.readBytes(person,16);var p=ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN);
                if(p.getLong(0)!=base+0x4565018)continue; // Human component route differs; only standalone controls here.
                long uid=Integer.toUnsignedLong(p.getInt(12));int index=p.getInt(8);
                assertThat(uid).isEqualTo(((Number)row.get("UNIQUE_ID")).longValue());assertThat(index).isBetween(0,Math.toIntExact((end-first)/8-1));
                assertThat(memory.readU64(first+index*8L)).isEqualTo(person);
                long component=person-0x100,member=component+8;byte[] raw=memory.readBytes(member,64);
                assertThat(ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getLong()).isEqualTo(base+0x449B2E8);
                for(var definition:com.github.fmaiassistent.staff.StaffAttributeDefinitions.ALL)if(List.of("fitness","defending","attacking").contains(definition.key())) {
                    int stored=((Number)row.get(definition.key().toUpperCase(Locale.ROOT))).intValue();
                    int value=Byte.toUnsignedInt(raw[8+definition.offset()]);
                    if(stored!=0||value<1||value>2)continue;
                    var sample=new LinkedHashMap<String,Object>();sample.put("uid",uid);sample.put("name",row.get("NAME"));sample.put("club",row.get("CLUB"));
                    sample.put("job",row.get("JOB"));sample.put("javaLabel",definition.key());sample.put("profileByteOffset",definition.offset());
                    sample.put("raw",value);sample.put("storedJavaValue",stored);sample.put("nativeNormalizedCandidate",1);
                    sample.put("personAddress",Long.toHexString(person));sample.put("personPrefixHex",HexFormat.of().formatHex(prefix));
                    sample.put("member64Hex",HexFormat.of().formatHex(raw));samples.add(sample);
                }
                assertThat(memory.readBytes(person,16)).isEqualTo(prefix);assertThat(memory.readBytes(member,64)).isEqualTo(raw);
                assertThat(memory.readU64(first+index*8L)).isEqualTo(person);
            }
            assertThat(memory.readU64(base+0x4E495E0)).isEqualTo(descriptor);assertThat(memory.readU64(descriptor+0x80)).isEqualTo(header);
            assertThat(memory.readU64(header)).isEqualTo(first);assertThat(memory.readU64(header+8)).isEqualTo(end);
        }
        // Empty is legitimate after the repair; this method is an opt-in diagnostic, not a required bug count.
        Files.write(Path.of(System.getProperty("fm26.staff.normalization.report")),JsonMapper.builder().build().writeValueAsBytes(Map.of(
                "pid",pid,"operation","read-only actualDB plus targeted registered staff member reads","samples",samples,
                "limitation","Actual raw1/2->stored0; native flag-normalized branch would yield1, but semantic UI consumer/flag and labels not proven")),StandardOpenOption.CREATE_NEW);
    }
    @Test void capturedNormalizerMatchesSignedByteClosedFormAcrossAll256Inputs()throws Exception {
        var mapper=JsonMapper.builder().build();var catalogue=mapper.readTree(read("nonplayer_selector_catalogue_checked_20261025.json",131072));
        var live=mapper.readTree(read("nonplayer_selector_code_table_live_20261025.json",16384)).get("returnValues").get(0);
        var code=HexFormat.of().parseHex(live.get("functionHex").asString());
        assertThat(Arrays.copyOfRange(code,0x2350-0x2040,0x2388-0x2040)).isEqualTo(HexFormat.of().parseHex(catalogue.get("normalizerHex").asString()));
        assertThat(Arrays.copyOfRange(code,0x2348-0x2040,0x2350-0x2040))
                .isEqualTo(HexFormat.of().parseHex("84db0f84c5fdffff")); // Flag==0 jumps to return.
        assertThat(catalogue.get("normalizerHex").asString()).startsWith("0fbec09869c06766000005cecc0000"); // Signed byte, multiply, add.
        int differing=0;
        for(var value:catalogue.get("exhaustiveByteComparison")) {
            int raw=value.get("raw").asInt();int signed=(byte)raw;
            int nativeValue=Math.max(1,Math.min(20,Math.floorDiv(signed+2,5)));
            assertThat(value.get("native").asInt()).isEqualTo(nativeValue);
            if(value.get("java").asInt()!=nativeValue)differing++;
        }
        assertThat(catalogue.get("exhaustiveByteComparison").size()).isEqualTo(256);assertThat(differing).isEqualTo(131);
    }

    @Test void all45SelectorTargetsAndAllObservedFormulaDifferencesHaveRetainedRawEvidence()throws Exception {
        var mapper=JsonMapper.builder().build();var catalogue=mapper.readTree(read("nonplayer_selector_catalogue_checked_20261025.json",131072));
        var live=mapper.readTree(read("nonplayer_selector_code_table_live_20261025.json",16384)).get("returnValues").get(0);
        var table=ByteBuffer.wrap(HexFormat.of().parseHex(live.get("tableHex").asString())).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(catalogue.get("selectorRows").size()).isEqualTo(45);
        for(var row:catalogue.get("selectorRows")) {
            int target=0x45659B4+table.getInt(row.get("selector").asInt()*4);
            assertThat(Integer.parseInt(row.get("targetRva").asString(),16)).isEqualTo(target);
            assertThat(target).isBetween(0x15B2040,0x15B2724);
        }
        var members=new String(read("all_combined_profiles_live_20261025.csv",1048576),java.nio.charset.StandardCharsets.UTF_8)
                .lines().skip(1).map(line->HexFormat.of().parseHex(line.split(",",-1)[7])).toList();
        assertThat(members).hasSize(1408);int total=0;
        assertThat(catalogue.get("javaComparisons").size()).isEqualTo(21);
        for(var comparison:catalogue.get("javaComparisons")) {
            assertThat(comparison.get("semanticNameStatus").asString()).isEqualTo("UNVERIFIED");
            int offset=8+comparison.get("profileByteOffset").asInt(),count=0;
            boolean direct=comparison.get("mode").asString().equals("UNSIGNED_BYTE_DIRECT_RETURN");
            for(var member:members)if(!direct) {
                int raw=Byte.toUnsignedInt(member[offset]);
                int nativeValue=Math.max(1,Math.min(20,Math.floorDiv((byte)raw+2,5)));
                if(nativeValue!=Math.max(0,Math.min(20,Math.round(raw/5f))))count++;
            }
            assertThat(comparison.get("normalizationDifferenceCandidates").asInt()).isEqualTo(count);total+=count;
        }
        assertThat(total).isEqualTo(914); // Repeated owner/byte comparisons, not914players or proven UI bugs.
    }
    private static byte[] read(String name,int cap)throws Exception {
        try(var input=Files.newInputStream(ROOT.resolve(name))){var bytes=input.readNBytes(cap+1);assertThat(bytes.length).isLessThanOrEqualTo(cap);return bytes;}
    }
}
