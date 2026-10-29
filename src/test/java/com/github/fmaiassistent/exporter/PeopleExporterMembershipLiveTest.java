package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.memory.ProcessReaders;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Native player projection is an independent oracle; production classifier is not used to build it. */
@Tag("fm26-live")
@EnabledIfSystemProperty(named="fm26.live",matches="true")
class PeopleExporterMembershipLiveTest {
    private record NativePlayer(long person,int index,String primaryType,byte[] prefix) { }
    private record PersonRtti(String name,int offset,int cdOffset,String getterRva,String getterEntry) { }

    @Test
    @EnabledIfSystemProperty(named="fm26.live.people.metadata",matches="true")
    void captureNativeNonPlayerProjectionMetadataWithoutInvokingGetters() throws Exception {
        int pid=Integer.getInteger("fm26.live.pid",-1);assertThat(pid).isPositive();
        try(var reader=ProcessReaders.open(pid)) {
            long base=FmOffsets.findGamePluginBase(reader);
            var module=reader.maps().stream().filter(r->r.contains(base,1)&&!r.path().isBlank()).map(r->Path.of(r.path())).findFirst().orElseThrow();
            assertThat(ContractExporter.supportedModule(module)).isTrue();
            long descriptor=reader.readU64(base+0x4E495E0),header=reader.readU64(descriptor+0x80);
            byte[] bounds=reader.readBytes(header,24);long a=little(bounds).getLong(),z=little(bounds).getLong(8);
            assertThat(z-a).isBetween(8L,4_000_000L);assertThat((z-a)%8).isZero();byte[] pointers=reader.readBytes(a,Math.toIntExact(z-a));
            Map<Long,PersonRtti> metadata=new TreeMap<>();Map<Long,Map<String,Object>> byMostDerived=new HashMap<>();Map<String,Long> personTypes=new TreeMap<>();
            Map<Long,Map<String,Object>> byComponent=new HashMap<>();Map<Long,byte[]> personPrefixes=new HashMap<>();
            var slots=little(pointers);
            for(int i=0;i<pointers.length/8;i++) {
                long person=slots.getLong(i*8);if(person==0)continue;
                byte[] prefix=reader.readBytes(person,16);assertThat(little(prefix).getInt(8)).isEqualTo(i);
                long vtable=little(prefix).getLong();var type=metadata.get(vtable);
                if(type==null) {
                    assertThat(vtable-base).isBetween(8L,0x1EB40000L-0x700);
                    long col=reader.readU64(vtable-8);assertThat(col-base).isBetween(0L,0x1EB40000L-24);
                    byte[] record=reader.readBytes(col,24);var c=little(record);assertThat(c.getInt()).isEqualTo(1);
                    int offset=c.getInt(4),cd=c.getInt(8);assertThat(offset).isBetween(0,65536);assertThat(cd).isBetween(0,4096);
                    long td=Integer.toUnsignedLong(c.getInt(12));assertThat(td).isBetween(1L,0x1EB40000L-176);
                    byte[] name=reader.readBytes(base+td+16,160);int n=0;while(n<name.length&&name[n]!=0)n++;assertThat(n).isLessThan(name.length);
                    String typeName=new String(name,0,n,java.nio.charset.StandardCharsets.US_ASCII);
                    String getter=null,entry=null;
                    if(Set.of(".?AVACTUAL_NON_PLAYER@db@@",".?AVHUMAN_NON_PLAYER@db@@",".?AVACTUAL_PLAYER_AND_NON_PLAYER@db@@",".?AVACTUAL_PLAYER@db@@").contains(typeName)) {
                        long target=reader.readU64(vtable+0x698);assertThat(target-base).isBetween(0L,0x1EB40000L-64);
                        getter=Long.toHexString(target-base);entry=HexFormat.of().formatHex(reader.readBytes(target,64));
                    }
                    type=new PersonRtti(typeName,offset,cd,getter,entry);metadata.put(vtable,type);
                }
                int displacement=type.cdOffset()==0?0:reader.readI32(person-type.cdOffset());
                long mostDerived=person-type.offset()-displacement;
                assertThat(byMostDerived.put(mostDerived,Map.of("person",Long.toHexString(person),"uid",Integer.toUnsignedLong(little(prefix).getInt(12)),"index",i,"type",type.name())))
                        .as("Distinct PERSON records should not alias one complete object").isNull();
                personTypes.merge(type.name(),1L,Long::sum);
                // Independently retained native PERSON v698 getter bodies, not classifier offsets.
                long adjustment=switch(type.name()) {
                    case ".?AVACTUAL_NON_PLAYER@db@@", ".?AVACTUAL_PLAYER_AND_NON_PLAYER@db@@" -> 0x100L;
                    case ".?AVHUMAN_NON_PLAYER@db@@" -> 0x450L;
                    default -> 0L;
                };
                if(adjustment!=0) {
                    String expected=adjustment==0x100L?"31c02b41fc4898488d840100ffffffc3":"31c02b41fc4898488d8401b0fbffffc3";
                    assertThat(type.getterEntry()).startsWith(expected);
                    long component=person-reader.readI32(person-4)-adjustment;
                    assertThat(byComponent.put(component,byMostDerived.get(mostDerived))).isNull();
                    personPrefixes.put(person,prefix);
                }
            }
            long projectionHeader=base+0x4E49578+0x148;byte[] projectionBounds=reader.readBytes(projectionHeader,24);
            long first=little(projectionBounds).getLong(),last=little(projectionBounds).getLong(8);
            assertThat(last-first).isBetween(8L,800_000L);assertThat((last-first)%8).isZero();byte[] projection=reader.readBytes(first,Math.toIntExact(last-first));
            Map<String,Long> matchedTypes=new TreeMap<>();List<Map<String,Object>> unmatched=new ArrayList<>();long matched=0,missing=0;
            var data=little(projection);Map<Long,Map<String,Object>> expectedStaff=new TreeMap<>();Map<String,Long> resolvedTypes=new TreeMap<>();Set<Long> seenComponents=new HashSet<>();
            for(int i=0;i<projection.length/8;i++) {
                long component=data.getLong(i*8);var person=byMostDerived.get(component);
                assertThat(seenComponents.add(component)).isTrue();
                var resolved=byComponent.get(component);assertThat(resolved).as("Native v698 component must resolve to PERSON").isNotNull();
                String resolvedType=String.valueOf(resolved.get("type"));resolvedTypes.merge(resolvedType,1L,Long::sum);
                if(!resolvedType.equals(".?AVACTUAL_PLAYER_AND_NON_PLAYER@db@@"))
                    assertThat(expectedStaff.put(((Number)resolved.get("uid")).longValue(),resolved)).isNull();
                if(person==null){missing++;if(unmatched.size()<100)unmatched.add(Map.of("slot",i,"component",Long.toHexString(component),"prefix",HexFormat.of().formatHex(reader.readBytes(component,16))));}
                else{matched++;matchedTypes.merge(String.valueOf(person.get("type")),1L,Long::sum);}
            }
            assertThat(reader.readU64(base+0x4E495E0)).isEqualTo(descriptor);assertThat(reader.readU64(descriptor+0x80)).isEqualTo(header);
            assertThat(reader.readBytes(header,24)).isEqualTo(bounds);assertThat(reader.readBytes(a,pointers.length)).isEqualTo(pointers);
            assertThat(reader.readBytes(projectionHeader,24)).isEqualTo(projectionBounds);assertThat(reader.readBytes(first,projection.length)).isEqualTo(projection);
            var report=new LinkedHashMap<String,Object>();report.put("pid",pid);report.put("moduleBase",Long.toHexString(base));report.put("status","METADATA_ONLY");
            report.put("personTypes",personTypes);report.put("personVtableMetadata",metadata);report.put("projectionCount",projection.length/8);
            report.put("directMostDerivedMatches",matched);report.put("directMatchTypes",matchedTypes);report.put("unmatchedComponents",missing);report.put("first100Unmatched",unmatched);
            report.put("limitation","Native RTTI and component metadata only. Non-player component pointers may not be complete-object bases. No getter invocation or Java staff membership proof.");
            report.put("resolvedNativeComponentTypes",resolvedTypes);
            if(Boolean.getBoolean("fm26.live.people.staffMembership")) {
                long started=System.nanoTime();var exported=new PeopleExporter().exportAllPeople(pid,0x238BDD,base);
                report.put("fullPeopleExportElapsedMs",(System.nanoTime()-started)/1_000_000L);
                Set<Long> actual=new TreeSet<>(),duplicates=new TreeSet<>();List<Map<String,Object>> mismatches=new ArrayList<>();
                for(var row:exported.staff().rows()) {
                    long uid=((Number)row.get("unique_id")).longValue();if(!actual.add(uid))duplicates.add(uid);
                    var nativeStaff=expectedStaff.get(uid);
                    if(nativeStaff!=null&&(!Long.toHexString(Long.decode(String.valueOf(row.get("record_address")))).equals(nativeStaff.get("person"))
                            ||((Number)row.get("staff_index")).intValue()!=((Number)nativeStaff.get("index")).intValue()))
                        if(mismatches.size()<100)mismatches.add(Map.of("uid",uid,"javaPerson",row.get("record_address"),"nativePerson",nativeStaff.get("person")));
                }
                Set<Long> absent=new TreeSet<>(expectedStaff.keySet());absent.removeAll(actual);Set<Long> extra=new TreeSet<>(actual);extra.removeAll(expectedStaff.keySet());
                report.put("nativeStandaloneStaff",expectedStaff.size());report.put("javaStaff",actual.size());report.put("nativeStaffUidSha256",uidDigest(expectedStaff.keySet()));report.put("javaStaffUidSha256",uidDigest(actual));
                report.put("missingStaffFirst100",absent.stream().limit(100).toList());report.put("extraStaffFirst100",extra.stream().limit(100).toList());report.put("duplicateStaffFirst100",duplicates.stream().limit(100).toList());report.put("identityMismatchesFirst100",mismatches);
                assertThat(absent).isEmpty();assertThat(extra).isEmpty();assertThat(duplicates).isEmpty();assertThat(mismatches).isEmpty();
                assertThat(reader.readU64(base+0x4E495E0)).isEqualTo(descriptor);assertThat(reader.readU64(descriptor+0x80)).isEqualTo(header);
                assertThat(reader.readBytes(header,24)).isEqualTo(bounds);assertThat(reader.readBytes(a,pointers.length)).isEqualTo(pointers);
                assertThat(reader.readBytes(projectionHeader,24)).isEqualTo(projectionBounds);assertThat(reader.readBytes(first,projection.length)).isEqualTo(projection);
                for(var entry:personPrefixes.entrySet())assertThat(reader.readBytes(entry.getKey(),16)).isEqualTo(entry.getValue());
                report.put("status","STAFF_MEMBERSHIP_MATCH");report.put("finalGuardsUnchanged",true);
                report.put("limitation","Standalone staff membership and PERSON identity in this capture only; combined objects are explicitly accounted in native projection but remain player output. Staff field semantics, combined staff-role coverage, persistence and reload stability not proven.");
            }
            String output=System.getProperty("fm26.live.people.metadata.report");if(output!=null)Files.write(Path.of(output),JsonMapper.builder().build().writeValueAsBytes(report),java.nio.file.StandardOpenOption.CREATE_NEW);
        }
    }

    @Test
    void completeApplicationExportMatchesNativePlayerProjectionByUidAndPerson() throws Exception {
        int pid=Integer.getInteger("fm26.live.pid",-1);
        assertThat(pid).isPositive();
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("pid",pid);report.put("status","STARTED");
        report.put("scope","Full production COMBINED PeopleExporter player membership; staff fields/membership not independently established by this test");
        try(var reader=ProcessReaders.open(pid)) {
            long base=FmOffsets.findGamePluginBase(reader);
            var path=reader.maps().stream().filter(r->r.contains(base,1)&&!r.path().isBlank()).map(r->Path.of(r.path())).findFirst().orElseThrow();
            assertThat(ContractExporter.supportedModule(path)).isTrue();
            report.put("pluginSha256",ContractExporter.MODULE_SHA256);report.put("moduleBase",Long.toHexString(base));
            // Roots/adjustments from B67C30 projection population and retained typed runtime analysis.
            long projectionHeader=base+0x4E49578+0x100;
            byte[] projectionHeaderRaw=reader.readBytes(projectionHeader,24);
            var ph=little(projectionHeaderRaw);long begin=ph.getLong(0),end=ph.getLong(8);
            assertThat(end-begin).isBetween(8L,800_000L);assertThat((end-begin)%8).isZero();
            byte[] projection=reader.readBytes(begin,Math.toIntExact(end-begin));
            long peopleDescriptor=reader.readU64(base+0x4E495E0),peopleHeader=reader.readU64(peopleDescriptor+0x80);
            byte[] peopleHeaderRaw=reader.readBytes(peopleHeader,24);
            long peopleBegin=little(peopleHeaderRaw).getLong(0),peopleEnd=little(peopleHeaderRaw).getLong(8);
            assertThat(peopleEnd-peopleBegin).isBetween(8L,4_000_000L);assertThat((peopleEnd-peopleBegin)%8).isZero();
            byte[] peoplePointers=reader.readBytes(peopleBegin,Math.toIntExact(peopleEnd-peopleBegin));
            Map<Long,NativePlayer> expected=new TreeMap<>();Map<String,Long> types=new TreeMap<>();
            var pointers=little(projection);var personSlots=little(peoplePointers);
            for(int i=0;i<projection.length/8;i++) {
                long primary=pointers.getLong(i*8),vtable=reader.readU64(primary);
                assertThat(vtable-base).isIn(0x4509828L,0x4785308L);
                boolean combined=vtable-base==0x4785308L;
                long person=primary+(combined?0x380:0x288);
                byte[] prefix=reader.readBytes(person,16);int index=little(prefix).getInt(8);
                long uid=Integer.toUnsignedLong(little(prefix).getInt(12));
                assertThat(index).isBetween(0,peoplePointers.length/8-1);assertThat(uid).isPositive();
                assertThat(personSlots.getLong(index*8)).isEqualTo(person);
                String type=combined?"ACTUAL_PLAYER_AND_NON_PLAYER":"ACTUAL_PLAYER";
                assertThat(expected.put(uid,new NativePlayer(person,index,type,prefix))).as("Native duplicate playerUID %s",uid).isNull();
                types.merge(type,1L,Long::sum);
            }
            report.put("nativePlayers",expected.size());report.put("nativePersonSlots",peoplePointers.length/8);report.put("nativePrimaryTypes",types);
            report.put("nativeUidSetSha256",uidDigest(expected.keySet()));report.put("status","NATIVE_ORACLE_READY");
            var exporter=new PeopleExporter();
            long started=System.nanoTime();
            var exported=exporter.exportAllPeople(pid,0x238BDD,base);
            report.put("fullPeopleExportElapsedMs",(System.nanoTime()-started)/1_000_000);
            report.put("exporterDiagnostics",exported.diagnostics());report.put("workers",exported.selectedWorkers());
            report.put("gameDate",exported.players().gameDate());report.put("playerRows",exported.players().rows().size());
            report.put("staffRows",exported.staff().rows().size());report.put("status","EXPORT_FINISHED");
            Set<Long> actual=new TreeSet<>(),duplicates=new TreeSet<>();List<Map<String,Object>> mismatches=new ArrayList<>();
            long contracts=0,terms=0;
            for(var row:exported.players().rows()) {
                long uid=((Number)row.get("unique_id")).longValue();
                if(!actual.add(uid))duplicates.add(uid);
                var nativePlayer=expected.get(uid);
                long person=Long.decode(String.valueOf(row.get("record")));
                if(nativePlayer!=null&&(person!=nativePlayer.person()||((Number)row.get("index")).intValue()!=nativePlayer.index()))
                    if(mismatches.size()<100)mismatches.add(Map.of("uid",uid,"javaPerson",Long.toHexString(person),"nativePerson",Long.toHexString(nativePlayer.person()),"javaIndex",row.get("index"),"nativeIndex",nativePlayer.index()));
                Object list=row.get("_contracts");
                if(list instanceof List<?> items){contracts+=items.size();for(Object item:items)terms+=((com.github.fmaiassistent.domain.ContractSnapshot)item).terms().size();}
            }
            Set<Long> missing=new TreeSet<>(expected.keySet());missing.removeAll(actual);
            Set<Long> extra=new TreeSet<>(actual);extra.removeAll(expected.keySet());
            report.put("actualUidSetSha256",uidDigest(actual));report.put("duplicateUidCount",duplicates.size());report.put("duplicateUids",duplicates.stream().limit(100).toList());
            report.put("missingUidCount",missing.size());report.put("missingUids",missing.stream().limit(100).toList());
            report.put("extraUidCount",extra.size());report.put("extraUids",extra.stream().limit(100).toList());report.put("identityMismatchesFirst100",mismatches);
            report.put("contracts",contracts);report.put("terms",terms);
            long staffContracts=0,staffTerms=0;
            Map<String,Long> staffContractKinds=new TreeMap<>();
            for(var row:exported.staff().rows()) {
                assertThat(row.get("_contract_profile")).isEqualTo(ContractExporter.MODULE_SHA256);
                assertThat(row.get("_contracts")).isInstanceOf(List.class);
                for(Object item:(List<?>)row.get("_contracts")) {
                    var contract=(com.github.fmaiassistent.domain.ContractSnapshot)item;
                    assertThat(contract.ownerUid()).isEqualTo(((Number)row.get("unique_id")).longValue());
                    staffContracts++;staffTerms+=contract.terms().size();
                    staffContractKinds.merge(contract.kind(),1L,Long::sum);
                    if("BASIC".equals(contract.kind())) {
                        assertThat(contract.coreHex()).hasSize(80);
                        if(contract.slot()==-1)assertThat(row.get("contract_end_date")).isEqualTo("");
                    }
                }
            }
            report.put("staffContracts",staffContracts);report.put("staffTerms",staffTerms);
            report.put("staffContractKinds",staffContractKinds);
            report.put("staffContractLimitation","Production capture and owner UID checks only; independent native semantics cover retained samples, not every staff term.");
            assertThat(reader.readBytes(projectionHeader,24)).isEqualTo(projectionHeaderRaw);
            assertThat(reader.readBytes(begin,projection.length)).isEqualTo(projection);
            assertThat(reader.readU64(base+0x4E495E0)).isEqualTo(peopleDescriptor);
            assertThat(reader.readU64(peopleDescriptor+0x80)).isEqualTo(peopleHeader);
            assertThat(reader.readBytes(peopleHeader,24)).isEqualTo(peopleHeaderRaw);
            assertThat(reader.readBytes(peopleBegin,peoplePointers.length)).isEqualTo(peoplePointers);
            for(var player:expected.values())assertThat(reader.readBytes(player.person(),16)).isEqualTo(player.prefix());
            report.put("nativeRootSlotPrefixGuardsUnchanged",true);
            report.put("status",missing.isEmpty()&&extra.isEmpty()&&duplicates.isEmpty()&&mismatches.isEmpty()?"MEMBERSHIP_MATCH":"DISCREPANCY");
            assertThat(missing).as("Missing native playerUIDs; report retains first100").isEmpty();
            assertThat(extra).as("Non-native playerUIDs in Java export").isEmpty();
            assertThat(duplicates).isEmpty();assertThat(mismatches).isEmpty();
        } catch(Throwable error) {
            report.put("errorClass",error.getClass().getName());
            if(!"DISCREPANCY".equals(report.get("status")))report.put("status","ERROR");
            throw error;
        } finally {
            String output=System.getProperty("fm26.live.people.membership.report");
            if(output!=null)Files.write(Path.of(output),JsonMapper.builder().build().writeValueAsBytes(report),java.nio.file.StandardOpenOption.CREATE_NEW);
        }
    }

    private static ByteBuffer little(byte[] bytes){return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);}
    private static String uidDigest(Collection<Long> values)throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        for(long value:values)digest.update((value+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(digest.digest());
    }
}
