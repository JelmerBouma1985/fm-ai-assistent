package com.github.fmaiassistent.memory;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Full targeted prefix/link receipts, not an independent live observation or complete field interpretation. */
class Fm26CapturedClubIdentityEvidenceTest {
    private static final Path EVIDENCE=Path.of("docs/reverse-engineering/fm26/evidence");
    private record Club(int index,long uid,long address,String type,byte[] prefix) { }

    @Test
    @org.junit.jupiter.api.Tag("fm26-live")
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="fm26.delivery.database",matches="true")
    void existingDatabaseViewsMatchTheCoveredNativeClubSetButNotTheEntireRegistry()throws Exception {
        String url=System.getProperty("fm26.delivery.database.url");assertThat(url).contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE").startsWith("jdbc:h2:file:");
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url,"sa",""));
        assertThat(jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='fm_pid'",String.class)).isEqualTo("22740");
        var nativeClubs=clubs();var stored=new HashMap<Long,Integer>();
        jdbc.query("SELECT source_address,COUNT(*) AS n FROM clubs GROUP BY source_address",(org.springframework.jdbc.core.RowCallbackHandler)r->{
            stored.put(r.getLong(1),r.getInt(2));
        });
        var eligible=new HashSet<Long>();var expectedContexts=new HashMap<String,Integer>();
        for(String line:lines("club_team_links_live_20261025.csv")) {
            var c=line.split(",",-1);if(hexLong(c[8])!=0) {
                long owner=hexLong(c[6]),competition=hexLong(c[8]);eligible.add(owner);
                expectedContexts.merge(owner+":"+competition,Integer.parseInt(c[10]),Math::max);
            }
        }
        assertThat(stored.keySet()).containsExactlyInAnyOrderElementsOf(eligible);
        assertThat(stored.keySet()).allMatch(nativeClubs::containsKey);
        assertThat(stored).hasSize(23972);assertThat(stored.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(31610);
        assertThat(stored.values().stream().filter(n->n>1).count()).isEqualTo(6629);
        long missing=nativeClubs.values().stream().filter(c->!stored.containsKey(c.address())).count();assertThat(missing).isEqualTo(2867);
        var actualContexts=new HashMap<String,Integer>();
        int[] missingFk={0};var missingExamples=new ArrayList<Map<String,Object>>();
        jdbc.query("SELECT c.source_address,co.source_address,c.reputation,c.name,c.competition FROM clubs c LEFT JOIN competitions co ON co.id=c.competition_id",(org.springframework.jdbc.core.RowCallbackHandler)r->{
            long owner=r.getLong(1),competition=r.getLong(2);
            if(r.wasNull()) {
                missingFk[0]++;if(missingExamples.size()<8)missingExamples.add(Map.of("club",r.getString(4),"competition",r.getString(5),"clubAddress",Long.toHexString(owner)));
                return;
            }
            assertThat(actualContexts.put(owner+":"+competition,r.getInt(3))).as("Unique stored club/competition pointer context").isNull();
        });
        String output=System.getProperty("fm26.club.identity.database.report");
        var report=new LinkedHashMap<String,Object>(Map.of(
                "pid",22740,"database","actual user file database, read-only","nativeClubRecords",nativeClubs.size(),
                "exportedPhysicalClubRecords",stored.size(),"storedViewRows",31610,"clubsWithMultipleViewRows",6629,
                "nativeRecordsMissingFromViews",missing,"nativeCoveredSetEqualsStoredAddressSet",true,
                "limitation","Source-address set/row multiplicity at this captured process only; field semantics, official intended UI population and canonical delivery still pending"));
        report.put("missingCompetitionForeignKeys",missingFk[0]);report.put("missingForeignKeyExamples",missingExamples);
        report.put("allCompetitionContextsAndMaxA8WordsMatch",actualContexts.equals(expectedContexts));
        if(output!=null)Files.write(Path.of(output),JsonMapper.builder().build().writeValueAsBytes(report),StandardOpenOption.CREATE_NEW);
        assertThat(missingFk[0]).as("Club competition foreign keys").isZero();
        assertThat(actualContexts).containsExactlyInAnyOrderEntriesOf(expectedContexts);
    }

    @Test void allRegisteredClubPrefixesHaveDistinctIdsAndTwoIndependentlyRecordedNativeTypes()throws Exception {
        var clubs=clubs();assertThat(clubs).hasSize(26839);
        assertThat(clubs.values().stream().map(Club::uid).distinct().count()).isEqualTo(26839);
        assertThat(clubs.values().stream().map(Club::index).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(java.util.stream.IntStream.range(0,26839).boxed().toList());
        var counts=clubs.values().stream().collect(Collectors.groupingBy(Club::type,Collectors.counting()));
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of("44BA518",26377L,"4740AB8",462L));
        var rtti=JsonMapper.builder().build().readTree(bounded("club_registry_types_live_20261022.json")).get("returnValues").get(0);
        for(var type:rtti.get("types")) {
            assertThat(type.get("count").asLong()).isEqualTo(counts.get(type.get("vtableRva").asString()));
            boolean clubBase=false;
            for(var base:type.get("bases"))if(base.get("name").asString().equals(".?AVCLUB@db@@")) {
                clubBase=true;assertThat(base.get("mdisp").asInt()).isZero();
            }
            assertThat(clubBase).isTrue();
        }
        var first=clubs.values().stream().filter(c->c.index()==0).findFirst().orElseThrow();
        assertThat(first.uid()).isEqualTo(58); // Native registry index is not the native CLUB ID.
        assertThat(Integer.toUnsignedLong(little(first.prefix()).getInt(8))).isNotEqualTo(first.uid());
    }

    @Test void allCapturedTeamLinksExplainMissingObjectsAndMultipleViewRowsWithoutSubstitutingTeamIds()throws Exception {
        var clubs=clubs();var teams=lines("club_team_links_live_20261025.csv");
        assertThat(teams).hasSize(59030);
        var attached=new HashSet<Long>();var covered=new HashSet<Long>();var contexts=new HashSet<String>();
        var teamIndices=new HashSet<Integer>();long differentUid=0,noCompetition=0;
        for(String line:teams) {
            var c=line.split(",",-1);assertThat(c).hasSize(12);
            int index=Integer.parseInt(c[0]);long uid=Long.parseLong(c[1]);long owner=hexLong(c[6]),competition=hexLong(c[8]);
            byte[] prefix=HexFormat.of().parseHex(c[4]),ownerRaw=HexFormat.of().parseHex(c[7]);
            assertThat(prefix).hasSize(16);assertThat(ownerRaw).hasSize(9);
            assertThat(little(prefix).getInt(8)).isEqualTo(index);assertThat(Integer.toUnsignedLong(little(prefix).getInt(12))).isEqualTo(uid);
            assertThat(little(prefix).getLong()).isEqualTo(0x7FF988310000L+0x44C13A8L);
            assertThat(c[3]).isEqualTo("44C13A8");assertThat(teamIndices.add(index)).isTrue();
            assertThat(ownerRaw[0]).isZero();assertThat(Integer.parseInt(c[5])).isZero();
            assertThat(little(ownerRaw).getLong(1)).isEqualTo(owner);
            assertThat(little(HexFormat.of().parseHex(c[9])).getLong()).isEqualTo(competition);
            int reputation=Integer.parseInt(c[10]);assertThat(reputation).isBetween(1,10000);
            assertThat(Short.toUnsignedInt(little(HexFormat.of().parseHex(c[11])).getShort())).isEqualTo(reputation);
            assertThat(clubs).containsKey(owner);attached.add(owner);
            if(clubs.get(owner).uid()!=uid)differentUid++;
            if(competition==0)noCompetition++;else{covered.add(owner);contexts.add(owner+":"+competition);}
        }
        assertThat(differentUid).isEqualTo(32704);assertThat(noCompetition).isEqualTo(27309);
        assertThat(attached).hasSize(26331);assertThat(covered).hasSize(23972);assertThat(contexts).hasSize(31610);
        long ordinaryMissing=clubs.values().stream().filter(c->c.type().equals("44BA518")&&!covered.contains(c.address())).count();
        long leagueMissing=clubs.values().stream().filter(c->c.type().equals("4740AB8")&&!covered.contains(c.address())).count();
        assertThat(ordinaryMissing).isEqualTo(2405);assertThat(leagueMissing).isEqualTo(462);
        var report=new LinkedHashMap<String,Object>();report.put("clubRecords",clubs.size());report.put("teamLinks",teams.size());
        report.put("coveredClubRecords",covered.size());report.put("ordinaryClubsWithoutEligibleTeam",ordinaryMissing);
        report.put("leagueBodyClubsWithoutEligibleTeam",leagueMissing);report.put("pointerContexts",contexts.size());
        report.put("teamIdDifferentFromOwnerClubId",differentUid);report.put("status","CAPTURE_SELF_CONSISTENCY_MATCH");
        report.put("limitation","Retained raw prefix/link decoding and RTTI cross-check; no new live observation, exporter equality, atomic generation or complete field semantics proof");
        String output=System.getProperty("fm26.club.identity.report");
        if(output!=null)Files.write(Path.of(output),JsonMapper.builder().build().writeValueAsBytes(report),StandardOpenOption.CREATE_NEW);
    }

    private static Map<Long,Club> clubs()throws Exception {
        var result=new HashMap<Long,Club>();
        for(var line:lines("club_registry_prefixes_live_20261025.csv")) {
            var c=line.split(",",-1);assertThat(c).hasSize(5);byte[] prefix=HexFormat.of().parseHex(c[4]);assertThat(prefix).hasSize(16);
            int index=Integer.parseInt(c[0]);long uid=Long.parseLong(c[1]),address=hexLong(c[2]);
            assertThat(little(prefix).getInt(8)).isEqualTo(index);assertThat(uid).isBetween(1L,0xFFFFFFFFL);
            assertThat(Integer.toUnsignedLong(little(prefix).getInt(12))).isEqualTo(uid);
            assertThat(little(prefix).getLong()).isEqualTo(0x7FF988310000L+hexLong(c[3]));
            assertThat(result.put(address,new Club(index,uid,address,c[3],prefix))).isNull();
        }
        return result;
    }
    private static List<String> lines(String name)throws Exception {
        return new String(bounded(name),StandardCharsets.UTF_8).lines().skip(1).filter(s->!s.isEmpty()).toList();
    }
    private static byte[] bounded(String name)throws IOException {
        try(var in=Files.newInputStream(EVIDENCE.resolve(name))) {
            byte[] raw=in.readNBytes(16*1024*1024+1);if(raw.length>16*1024*1024)throw new IOException("Oversized native fixture");return raw;
        }
    }
    private static long hexLong(String value){return Long.parseUnsignedLong(value,16);}
    private static ByteBuffer little(byte[] raw){return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);}
}
