package com.github.fmaiassistent.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Independent raw member/population evidence. No unproven staff-field names or production offsets. */
class Fm26CapturedCombinedProfilePopulationTest {
    private static final Path ROOT=Path.of("docs/reverse-engineering/fm26/evidence");
    private static final long BASE=0x7FF988310000L;
    private record Member(long uid,int index,long person,long component,long address,byte[] prefix,byte[] raw){}

    @Test void allCombinedProfilesHaveRegisteredIdentityAndTheSameNativeMemberRepresentation()throws Exception {
        var members=members();assertThat(members).hasSize(1408);
        assertThat(members).extracting(Member::uid).doesNotHaveDuplicates();
        assertThat(members).extracting(Member::index).doesNotHaveDuplicates();
        assertThat(members).extracting(Member::address).doesNotHaveDuplicates();
        var capture=JsonMapper.builder().build().readTree(bounded("all_combined_profiles_live_20261025.json",65536)).get("returnValues").get(0);
        assertThat(capture.get("allGuardsUnchanged").asBoolean()).isTrue();
        assertThat(capture.get("combinedProfiles").asInt()).isEqualTo(members.size());
        for(var member:members)validate(member,8);
    }

    @Test void shiftedMemberFailsOnCapturedBytesEvenWhenTheProfileValuesLookPlausible()throws Exception {
        for(var member:members())assertThatThrownBy(()->validate(member,16)).isInstanceOf(AssertionError.class);
    }

    @Test
    @Tag("fm26-live")
    @EnabledIfSystemProperty(named="fm26.combined.profile.database",matches="true")
    void everyCombinedPersonAlreadyHasAPlayerRowButNoStandaloneStaffRow()throws Exception {
        String url=System.getProperty("fm26.delivery.database.url");
        assertThat(url).startsWith("jdbc:h2:file:").contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));var members=members();
        var expected=new HashMap<Long,String>();for(var member:members)expected.put(member.uid,"0x"+Long.toHexString(member.person));
        int foundPlayers=0,foundStaff=0;
        for(int start=0;start<members.size();start+=128) {
            var chunk=members.subList(start,Math.min(start+128,members.size()));
            String marks=String.join(",",Collections.nCopies(chunk.size(),"?"));Object[] ids=chunk.stream().map(Member::uid).toArray();
            var rows=jdbc.queryForList("SELECT unique_id,record_address FROM players WHERE unique_id IN ("+marks+")",ids);
            foundPlayers+=rows.size();
            for(var row:rows)assertThat(row.get("RECORD_ADDRESS")).isEqualTo(expected.get(((Number)row.get("UNIQUE_ID")).longValue()));
            foundStaff+=jdbc.queryForObject("SELECT COUNT(*) FROM staff WHERE unique_id IN ("+marks+")",Integer.class,ids);
        }
        assertThat(foundPlayers).isEqualTo(1408);assertThat(foundStaff).isZero();
        String output=System.getProperty("fm26.combined.profile.database.report");
        if(output!=null)Files.write(Path.of(output),JsonMapper.builder().build().writeValueAsBytes(Map.of(
                "database","actual application H2 file, read-only","combinedProfiles",1408,"matchingPlayerRows",foundPlayers,
                "standaloneStaffRows",foundStaff,"memberPayloadBytes",1408*64,
                "limitation","Existing player row identity verified; raw NON_PLAYING_DATA members are not yet exported/persisted; native class is not an active staff job")),StandardOpenOption.CREATE_NEW);
    }

    private static void validate(Member m,int memberRelative) {
        assertThat(m.prefix).hasSize(16);assertThat(m.raw).hasSize(64);
        var prefix=little(m.prefix);assertThat(prefix.getLong(0)).isEqualTo(BASE+0x4785848);
        assertThat(prefix.getInt(8)).isEqualTo(m.index);assertThat(Integer.toUnsignedLong(prefix.getInt(12))).isEqualTo(m.uid);
        assertThat(m.person-m.component).isEqualTo(0x100);
        assertThat(m.address).isEqualTo(m.component+memberRelative);
        assertThat(little(m.raw).getLong(0)).isEqualTo(BASE+0x449B2E8);
        assertThat(Arrays.copyOfRange(m.raw,8,60)).hasSize(52);
        // Bytes60..63 remain unassigned; no all-zero/padding interpretation.
    }
    private static List<Member> members()throws Exception {
        String text=new String(bounded("all_combined_profiles_live_20261025.csv",1024*1024),java.nio.charset.StandardCharsets.UTF_8);
        var result=new ArrayList<Member>();var slots=new HashSet<Integer>();
        for(var line:text.lines().skip(1).toList()) {
            var c=line.split(",",-1);assertThat(c).hasSize(8);assertThat(slots.add(Integer.parseInt(c[0]))).isTrue();
            result.add(new Member(Long.parseLong(c[1]),Integer.parseInt(c[2]),hex(c[3]),hex(c[4]),hex(c[5]),
                    HexFormat.of().parseHex(c[6]),HexFormat.of().parseHex(c[7])));
        }
        return result;
    }
    private static byte[] bounded(String name,int limit)throws Exception {
        try(var input=Files.newInputStream(ROOT.resolve(name))) {
            byte[] bytes=input.readNBytes(limit+1);assertThat(bytes.length).isLessThanOrEqualTo(limit);return bytes;
        }
    }
    private static long hex(String s){return Long.parseUnsignedLong(s,16);}
    private static ByteBuffer little(byte[] b){return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);}
}
