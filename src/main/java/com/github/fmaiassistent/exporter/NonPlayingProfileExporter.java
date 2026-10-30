package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.NonPlayingProfileLayout;
import com.github.fmaiassistent.memory.PersonIdentityLayout;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.NativeModuleProfile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Optional native profile, distinct from staff employment. No native getters are invoked. */
public final class NonPlayingProfileExporter {
    private NonPlayingProfileExporter() { }

    /** The preceding contract capture verifies the binary once for these exact snapshot rows. */
    static void capture(ProcessMemoryReader reader,Long suppliedBase,List<Map<String,Object>> rows)throws IOException {
        if(reader.platform()!=ProcessMemoryReader.Platform.WINDOWS
                ||rows.stream().noneMatch(r->NativeModuleProfile.supported(String.valueOf(r.get("_contract_profile")))))return;
        long base=suppliedBase==null?com.github.fmaiassistent.linux.FmOffsets.findGamePluginBase(reader):suppliedBase;
        for (String profile : rows.stream().map(r->String.valueOf(r.get("_contract_profile"))).distinct()
                .filter(NativeModuleProfile::supported).toList()) {
            captureVerifiedRows(reader,base,rows.stream().filter(r->profile.equals(r.get("_contract_profile"))).toList(),
                    NonPlayingProfileLayout.current());
        }
    }

    static void captureVerifiedRows(ProcessMemoryReader reader,long base,List<Map<String,Object>> rows,
            NonPlayingProfileLayout layout)throws IOException {
        PlayerSnapshotValidator.validateNonPlayingProfileLayout(layout);
        var pending=new ArrayList<Pending>();
        for(var row:rows) {
            if(Thread.currentThread().isInterrupted())throw new IOException("Management profile capture interrupted");
            String profile=String.valueOf(row.get("_contract_profile"));
            if(!NativeModuleProfile.supported(profile))continue;
            if(!layout.equals(NonPlayingProfileLayout.current()))
                throw new IOException("Management profile layout does not match its source binary");
            Object address=row.containsKey("record")?row.get("record"):row.get("record_address");
            final long person;
            try { person=address instanceof Number n?n.longValue():Long.decode(String.valueOf(address)); }
            catch(NumberFormatException invalid){throw new IOException("Management profile requires a PERSON address",invalid);}
            Object rawIndex=row.containsKey("index")?row.get("index"):row.get("staff_index");
            if(!(rawIndex instanceof Number index)||!(row.get("unique_id") instanceof Number uid))
                throw new IOException("Management profile requires a PERSON index and UID");
            byte[] prefix=reader.readBytes(person,PersonIdentityLayout.current().prefixBytes());
            if(PlayerSnapshotValidator.validatePersonIdentity(index.intValue(),prefix,PersonIdentityLayout.current())!=uid.longValue())
                throw new IOException("Management profile owner UID changed");
            long vtable=ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN).getLong();
            boolean human=vtable==base+layout.humanPersonVtableRva();
            if(!human&&vtable!=base+layout.standalonePersonVtableRva()&&vtable!=base+layout.combinedPersonVtableRva())continue;
            // Both typed constructors embed the same member at owner+8; PERSON displacement is build-specific.
            if(reader.readU32(person+layout.constructionRel())!=0)throw new IOException("Management profile construction view changed");
            long member=person+(human?layout.humanPersonToMemberRel():layout.personToMemberRel());
            if(member<0x10000||member>ProcessMemoryReader.MAX_USER_ADDRESS-layout.memberBytes())
                throw new IOException("Management profile pointer/range anchor failed");
            byte[] raw=reader.readBytes(member,layout.memberBytes());
            PlayerSnapshotValidator.validateNonPlayingProfile(raw,base,layout);
            pending.add(new Pending(row,person,member,prefix,raw));
        }
        // Recheck all captured owners before attaching any payload to the exported snapshot.
        for(var p:pending) {
            if(!Arrays.equals(p.prefix(),reader.readBytes(p.person(),p.prefix().length))
                    ||reader.readU32(p.person()+layout.constructionRel())!=0
                    ||!Arrays.equals(p.raw(),reader.readBytes(p.member(),layout.memberBytes())))
                throw new IOException("Management profile changed during snapshot capture");
        }
        for(var p:pending) {
            p.row().put("non_playing_profile_hex",HexFormat.of().formatHex(
                    p.raw(),layout.profileOffset(),layout.profileOffset()+layout.profileBytes()));
            p.row().put("non_playing_profile_sha256",p.row().get("_contract_profile"));
        }
    }
    private record Pending(Map<String,Object> row,long person,long member,byte[] prefix,byte[] raw) { }
}
