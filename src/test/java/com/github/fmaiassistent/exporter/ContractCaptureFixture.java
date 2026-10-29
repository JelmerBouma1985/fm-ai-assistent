package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.domain.ContractSnapshot;
import com.github.fmaiassistent.memory.ContractRecordLayout;
import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Relocates fixture pointers only. Uncaptured TEAM links/primary bonus vectors are explicitly cleared. */
public final class ContractCaptureFixture implements ProcessMemoryReader {
    public static final long PERSON = 0x100000, FULL = 0x200000, ARRAY = 0x300000, LOAN = 0x400000;
    private final Map<Long, Byte> memory = new HashMap<>();
    public final long base;
    public final long uid;
    public int largestRead;
    private final Map<Long,Integer> readCounts=new HashMap<>();
    private long hookAddress;
    private int hookOrdinal;
    private Runnable readHook;

    public void onRead(long address,int ordinal,Runnable action){hookAddress=address;hookOrdinal=ordinal;readHook=action;}
    public int readCount(long address){return readCounts.getOrDefault(address,0);}

    private ContractCaptureFixture(long base, long uid) {
        this.base = base; this.uid = uid;
        put(PERSON, new byte[0xB8]);
        put(PERSON + 12, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) uid).array());
    }
    /** Two independently captured checkpoint56 owners; unrelated TEAM/other term spans deliberately excluded. */
    public static ContractCaptureFixture competitionBonus(int sample)throws Exception {
        var live=json("competition_bonus_references_checkpoint56_live_20261027.json").get("returnValues").get(0);
        var owner=live.get("owners").get(sample);
        var f=new ContractCaptureFixture(Long.parseUnsignedLong(live.get("moduleBase").asString(),16),owner.get("uid").asLong());
        byte[] core=hex(owner.get("core").asString());f.relocate(core);
        java.util.Arrays.fill(core,0x68,0xB0,(byte)0);
        buffer(core).putLong(0x80,0x500000).putLong(0x88,0x500020).putLong(0x90,0x500020);
        f.put(FULL,core);f.pointer(PERSON+0xA8,FULL);f.put(0x500000,hex(owner.get("rowsRaw").asString()));
        f.pointer(f.base+0x4E49590,0x600000);f.pointer(0x600080,0x601000);
        f.pointer(0x601000,0x700000);f.pointer(0x601008,0x700000+live.get("compRegistryCount").asLong()*8);
        f.pointer(0x601010,0x700000+live.get("compRegistryCount").asLong()*8);
        for(var comp:live.get("names")) {
            long address=Long.parseUnsignedLong(comp.get("address").asString(),16);
            f.put(address,hex(comp.get("prefix").asString()));f.pointer(0x700000+comp.get("index").asLong()*8,address);
            for(var name:comp.get("names"))if(!name.get("pointer").asString().equals("0"))
                f.put(Long.parseUnsignedLong(name.get("pointer").asString(),16),hex(name.get("raw").asString()));
        }
        return f;
    }

    public static ContractCaptureFixture full() throws Exception {
        var root = json("full_contract_addon_rows_live_20261022.json").get("returnValues").get(0);
        JsonNode selected = null;
        for (var row : root.get("rows")) if (row.get("uid").asLong() == 2002079297L) selected = row;
        if (selected == null) throw new IllegalStateException("Missing Sierd fixture");
        var f = new ContractCaptureFixture(Long.parseUnsignedLong(root.get("moduleBase").asString(), 16), selected.get("uid").asLong());
        byte[] core = hex(selected.get("raw").asString());
        f.relocate(core);
        java.util.Arrays.fill(core, 0x80, 0xA0, (byte) 0); // Other FULL collections are outside this captured fixture.
        f.put(FULL, core); f.pointer(PERSON + 0xA8, FULL);
        f.put(buffer(core).getLong(0x68), hex(selected.get("rowsRaw").asString()));
        return f;
    }
    public static ContractCaptureFixture loan() throws Exception {
        var root = json("typed_loan_playing_time_live_20261022.json").get("returnValues").get(0);
        JsonNode selected = null;
        for (var row : root.get("rows")) if (row.get("uid").asLong() == 2000091868L) selected = row;
        if (selected == null) throw new IllegalStateException("Missing Joao fixture");
        var f = new ContractCaptureFixture(Long.parseUnsignedLong(root.get("moduleBase").asString(), 16), selected.get("uid").asLong());
        byte[] primary = hex(selected.get("primaryRaw").asString()), loan = hex(selected.get("loanRaw").asString());
        f.relocate(primary); f.relocate(loan);
        java.util.Arrays.fill(primary, 0x68, 0xA0, (byte) 0); // Primary term spans were not captured in this fixture.
        f.put(FULL, primary); f.put(LOAN, loan); f.pointer(PERSON + 0xA8, FULL); f.pointer(PERSON + 0xB0, ARRAY);
        f.put(ARRAY, new byte[32]); f.pointer(ARRAY, LOAN);
        byte[] aux = hex(selected.get("auxiliaryRaw").asString()); f.put(buffer(loan).getLong(0x68), aux);
        for (var vector : selected.get("vectors")) {
            byte[] raw = hex(vector.get("raw").asString());
            if (raw.length > 0) f.put(buffer(aux).getLong(Integer.parseInt(vector.get("offset").asString(), 16)), raw);
        }
        return f;
    }
    public List<ContractSnapshot> decode() throws IOException {
        return new ContractExporter(this, base, ContractRecordLayout.current()).read(PERSON, uid);
    }
    /** All captured PERSON+A0 records, retaining their order and offer IDs.
     * Only selected FULL cores were captured; earlier nonmatching records must not be dereferenced. */
    public static ContractCaptureFixture futureEmployment(int sample) throws Exception {
        var root=json("future_employment_records_full_live_20261024.json").get("returnValues").get(0);
        var row=root.get("rows").get(sample);
        var f=new ContractCaptureFixture(Long.parseUnsignedLong(root.get("moduleBase").asString(),16),row.get("uid").asLong());
        f.put(PERSON,hex(row.get("person").get("prefix").asString()));
        f.fixtureRegistry(0x4E495E0,0x800000,0x801000,0x900000,row.get("person").get("index").asInt(),PERSON);
        f.pointer(PERSON+0xA0,0x500000);
        f.put(0x500000,hex(row.get("headerRaw").asString()));
        long table=buffer(hex(row.get("headerRaw").asString())).getLong();
        f.put(table,hex(row.get("pointerBytes").asString()));
        for(var record:row.get("records")) {
            long address=Long.parseUnsignedLong(record.get("address").asString(),16);
            f.put(address,hex(record.get("rawPrefix").asString()));
            if(!record.has("contractCore"))continue;
            byte[] core=hex(record.get("contractCore").asString());
            long contractAddress=Long.parseUnsignedLong(record.get("contract").asString(),16);
            buffer(core).putLong(8,PERSON);
            f.put(contractAddress,core);
            for(var v:record.get("collections")) {
                byte[] raw=hex(v.get("rawHex").asString());
                if(raw.length>0)f.put(Long.parseUnsignedLong(v.get("begin").asString(),16),raw);
            }
            long team=Long.parseUnsignedLong(record.get("team").get("address").asString(),16);
            long club=Long.parseUnsignedLong(record.get("club").get("address").asString(),16);
            byte[] teamBytes=new byte[0x38];
            System.arraycopy(hex(record.get("team").get("prefix").asString()),0,teamBytes,0,16);
            System.arraycopy(hex(record.get("team").get("ownerRaw").asString()),0,teamBytes,0x2F,9);
            f.put(team,teamBytes);f.put(club,hex(record.get("club").get("prefix").asString()));
            f.fixtureRegistry(0x4E49610,0x600000,0x601000,0x610000,record.get("team").get("index").asInt(),team);
            f.fixtureRegistry(0x4E49588,0x620000,0x621000,0x630000,record.get("club").get("index").asInt(),club);
        }
        return f;
    }

    /** Independent offer/PERSON98 capture joins the separately captured A0 contract by offer ID. */
    public static ContractCaptureFixture futureTransfer(int sample) throws Exception {
        var f=futureEmployment(sample);
        var root=json("future_transfer_status19_candidates_live_20261024.json").get("returnValues").get(0);
        var row=root.get("rows").get(sample);
        byte[] indices=hex(row.get("personIndices").get("raw").asString());
        f.pointer(PERSON+0x98,0xA00000);f.put(0xA00000,new byte[24]);
        f.pointer(0xA00000,0xA01000);f.pointer(0xA00008,0xA01000+indices.length);f.pointer(0xA00010,0xA01000+indices.length);
        f.put(0xA01000,indices);
        f.pointer(f.base+0x4E37720,0xB00000);f.pointer(0xB00000,f.base+0x4473F28);
        int size=root.get("collectionSlots").asInt()*8;
        f.pointer(0xB00008,0xC00000);f.pointer(0xB00010,0xC00000+size);f.pointer(0xB00018,0xC00000+size);
        f.put(0xC00000,new byte[size]);
        byte[] offer=hex(row.get("coreHex").asString());buffer(offer).putLong(0x38,PERSON);
        long address=Long.parseUnsignedLong(row.get("offer").asString(),16);
        f.put(address,offer);f.pointer(0xC00000+row.get("slot").asLong()*8,address);
        return f;
    }
    /** Actual standalone staff core, clauses, TEAM and CLUB bytes; relocates pointers only. */
    public static ContractCaptureFixture basicStaff(int sample) throws Exception {
        var capture=json("staff_basic_owners_guarded_live_20261024.json").get("returnValues").get(0);
        var row=capture.get("rows").get(sample);
        var f=new ContractCaptureFixture(Long.parseUnsignedLong(capture.get("pluginBase").asString(),16),row.get("uid").asLong());
        f.put(PERSON,hex(row.get("personPrefix").asString()));
        byte[] core=hex(row.get("coreHex").asString());f.relocate(core);
        long team=0x700000,club=0x710000;
        byte[] teamBytes=hex(row.get("teamPrefix").asString()),clubBytes=hex(row.get("clubPrefix").asString());
        buffer(teamBytes).putLong(0x30,club);buffer(core).putLong(0x10,team).putLong(0x18,team);
        f.put(team,teamBytes);f.put(club,clubBytes);f.put(FULL,core);f.pointer(PERSON+0xA8,FULL);
        f.fixtureRegistry(0x4E49610,0x600000,0x601000,0x610000,row.get("teamIndex").asInt(),team);
        f.fixtureRegistry(0x4E49588,0x620000,0x621000,0x630000,row.get("clubIndex").asInt(),club);
        return f;
    }

    /** Actual standalone staff FULL core and terms, relocating pointer links only. */
    public static ContractCaptureFixture standaloneStaff(int sample) throws Exception {
        var capture = json("standalone_staff_contract_terms_live_20261024.json").get("returnValues").get(0);
        var row = capture.get("rows").get(sample);
        var f = new ContractCaptureFixture(Long.parseUnsignedLong(capture.get("pluginBase").asString(), 16), row.get("uid").asLong());
        f.put(PERSON, hex(row.get("personPrefix").asString()));
        byte[] core = hex(row.get("coreHex").asString());
        f.relocate(core);
        long team = 0x700000, club = 0x710000, clauses = 0x720000;
        byte[] teamBytes = hex(row.get("teamPrefix").asString());
        byte[] clubBytes = hex(row.get("clubPrefix").asString());
        buffer(teamBytes).putLong(0x30, club);
        f.put(team, teamBytes); f.put(club, clubBytes); buffer(core).putLong(0x10, team);
        byte[] terms = hex(row.get("clauseRowsRaw").asString());
        buffer(core).putLong(0x68, clauses).putLong(0x70, clauses + terms.length).putLong(0x78, clauses + terms.length);
        f.put(clauses, terms); f.put(FULL, core); f.pointer(PERSON + 0xA8, FULL);
        f.fixtureRegistry(0x4E49610, 0x600000, 0x601000, 0x610000, row.get("teamIndex").asInt(), team);
        f.fixtureRegistry(0x4E49588, 0x620000, 0x621000, 0x630000, row.get("clubIndex").asInt(), club);
        return f;
    }
    /** Actual independent TEAM/owner bytes; the contract-to-national-TEAM link is deliberately constructed. */
    public static ContractCaptureFixture ownedTeam(boolean national) throws Exception {
        var f=full();
        var row=json("contract_team_owner_branches_live_20261023.json").get("returnValues").get(0).get("rows").get(national?1:0);
        long team=0x700000, owner=national?0x710000:0x710050;
        byte[] teamBytes=new byte[64];
        byte[] captured=hex(row.get("team").get("raw").asString());
        System.arraycopy(captured,0,teamBytes,0,captured.length);
        buffer(teamBytes).putLong(0,f.base+Long.parseUnsignedLong(row.get("team").get("vtableRva").asString(),16)).putLong(0x30,owner);
        byte[] ownerBytes=hex(row.get("owner").get("raw").asString());
        buffer(ownerBytes).putLong(0,f.base+Long.parseUnsignedLong(row.get("owner").get("vtableRva").asString(),16));
        f.put(team,teamBytes);f.put(owner,ownerBytes);f.pointer(FULL+0x10,team);
        f.fixtureRegistry(0x4E49610,0x600000,0x601000,0x610000,row.get("team").get("index").asInt(),team);
        f.fixtureRegistry(national?0x4E495D8:0x4E49588,0x620000,0x621000,0x630000,row.get("owner").get("index").asInt(),owner);
        return f;
    }
    private void fixtureRegistry(int rva,long descriptor,long header,long table,int index,long target) {
        pointer(base+rva,descriptor);pointer(descriptor+0x80,header);
        pointer(header,table);pointer(header+8,table+(index+1L)*8);pointer(table+index*8L,target);
    }
    /** Relocates captured row payloads into the two native-proven LOAN collections.
     * This exercises nonempty branches; it is not a capture of a nonempty loan in FM. */
    public static ContractCaptureFixture loanWithDirectTerms() throws Exception {
        var f = loan();
        var captured = json("contract_pipeline_selected_final_live_20261023.json").get("rows");
        byte[] competition = null;
        java.io.ByteArrayOutputStream awards = new java.io.ByteArrayOutputStream();
        for (var player : captured) for (var contract : player.get("_contracts")) for (var term : contract.get("terms")) {
            if (term.get("group").asString().equals("FULL_80")) competition = hex(term.get("rawHex").asString());
            if (term.get("group").asString().equals("FULL_98")) awards.writeBytes(hex(term.get("rawHex").asString()));
        }
        if (competition == null || competition.length != 32 || awards.size() != 32)
            throw new IllegalStateException("Missing captured competition/award row payloads");
        f.put(0x500000, competition);
        f.pointer(LOAN + 0x70, 0x500000); f.pointer(LOAN + 0x78, 0x500020); f.pointer(LOAN + 0x80, 0x500020);
        f.pointer(LOAN + 0x88, 0x510000);
        f.pointer(0x510000, 0x520000); f.pointer(0x510008, 0x520020); f.pointer(0x510010, 0x520020);
        f.put(0x520000, awards.toByteArray());
        var references = json("contract_award_references_live_20261023.json").get("returnValues").get(0);
        f.pointer(f.base + 0x4E49578, 0x530000); f.pointer(0x530080, 0x540000);
        f.pointer(0x540000, 0x550000); f.pointer(0x540008, 0x550000 + 3884 * 8L);
        for (var row : references.get("rows")) {
            long target = Long.parseUnsignedLong(row.get("target").asString(), 16);
            f.put(target, hex(row.get("prefix").asString()));
            f.pointer(target, f.base + 0x44D82B8); // Relocate captured type pointer to fixture's exact-build base.
            f.pointer(0x550000 + row.get("index").asLong() * 8, target);
        }
        return f;
    }
    public static ContractCaptureFixture bClub() throws Exception {
        var root = json("contract_samples_after_restart_live_20261021.json").get("returnValues").get(0);
        for (var sample : root.get("samples")) for (var extra : sample.get("extra")) {
            if (!extra.get("vptrRva").asString().equals("4725B38")) continue;
            var f = new ContractCaptureFixture(Long.parseUnsignedLong(root.get("moduleBase").asString(),16), sample.get("uid").asLong());
            byte[] core = hex(extra.get("bytes").asString()); f.relocate(core);
            f.put(LOAN,core); f.put(ARRAY,new byte[32]); f.pointer(ARRAY+8,LOAN); f.pointer(PERSON+0xB0,ARRAY);
            return f;
        }
        throw new IllegalStateException("Missing B_CLUB fixture");
    }
    private void relocate(byte[] core) { buffer(core).putLong(8, PERSON).putLong(0x10, 0); }
    public void pointer(long address, long value) { put(address, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()); }
    public void put(long address, byte[] value) { for (int i = 0; i < value.length; i++) memory.put(address + i, value[i]); }
    @Override public int pid() { return 1; }
    @Override public byte[] readBytes(long address, int size) throws IOException {
        int ordinal=readCounts.merge(address,1,Integer::sum);
        if(readHook!=null&&address==hookAddress&&ordinal==hookOrdinal){var action=readHook;readHook=null;action.run();}
        largestRead = Math.max(largestRead, size); byte[] result = new byte[size];
        for (int i = 0; i < size; i++) {
            Byte value = memory.get(address + i);
            if (value == null) throw new IOException("Unmapped fixture bytes");
            result[i] = value;
        }
        return result;
    }
    @Override public List<MemoryRegion> maps() { return List.of(); }
    @Override public void close() { }
    private static byte[] hex(String text) { return HexFormat.of().parseHex(text); }
    private static ByteBuffer buffer(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    private static JsonNode json(String name) throws Exception {
        try (var input = Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence", name))) {
            byte[] b = input.readNBytes(4_194_305);
            if (b.length > 4_194_304) throw new IOException("Oversized fixture");
            return JsonMapper.builder().build().readTree(b);
        }
    }
}
