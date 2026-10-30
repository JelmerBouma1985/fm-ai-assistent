package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.domain.ContractSnapshot;
import com.github.fmaiassistent.domain.FutureEmploymentSnapshot;
import com.github.fmaiassistent.memory.ContractRecordLayout;
import com.github.fmaiassistent.memory.FutureEmploymentLayout;
import com.github.fmaiassistent.memory.NativeModuleProfile;
import com.github.fmaiassistent.memory.ProcessMemoryReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDate;
import java.time.DateTimeException;

/** Evidence-backed Windows capture; registry metadata is scoped to one verified snapshot, never across captures. */
public final class ContractExporter {
    public static final String MODULE_SHA256 = "bc940e472418fc5c214208d7cf30fecf600f689b6f9d71c11ba19a2df0fe057f";
    public static final String MODULE_PROPERTY = "fmaiassistent.contracts.module";
    private static final int MAX_ROWS = 2048;
    private static final long MAX_CAPTURE_BYTES = 64L * 1024 * 1024;
    private final ProcessMemoryReader reader;
    private final long base;
    private final ContractRecordLayout layout;
    private long capturedBytes;
    private final Map<Integer,RegistrySnapshot> registries = new HashMap<>();
    private final Map<Long,byte[]> competitionIdentityGuards = new HashMap<>();

    public ContractExporter(ProcessMemoryReader reader, long base, ContractRecordLayout layout) {
        this.reader = reader;
        this.base = base;
        this.layout = layout;
    }

    /** Called once for player and staff rows before publication; unsupported profiles remain explicitly absent. */
    static void capture(ProcessMemoryReader reader, Long base, List<Map<String, Object>> rows) throws IOException {
        String status = "UNSUPPORTED_PLATFORM_OR_MODULE";
        if (reader.platform() == ProcessMemoryReader.Platform.WINDOWS) {
            long moduleBase = base == null ? com.github.fmaiassistent.linux.FmOffsets.findGamePluginBase(reader) : base;
            String override = System.getProperty(MODULE_PROPERTY);
            Path module = override == null || override.isBlank() ? reader.maps().stream()
                    .filter(r -> r.contains(moduleBase, 1) && !r.path().isBlank())
                    .map(r -> Path.of(r.path())).findFirst().orElse(null) : Path.of(override);
            var profile = module == null ? Optional.<String>empty() : NativeModuleProfile.identify(module);
            if (profile.isPresent()) {
                ContractExporter exporter = new ContractExporter(reader, moduleBase,
                        ContractRecordLayout.current());
                exporter.captureRows(rows);
                return;
            }
            status = "UNSUPPORTED_BINARY_HASH";
        }
        for (var row : rows) {
            row.put("_contract_profile", status);
            if(reader.platform()==ProcessMemoryReader.Platform.WINDOWS)row.put("_future_transfer_profile",status);
        }
    }

    static boolean supportedModule(Path path) throws IOException {
        return NativeModuleProfile.identify(path).isPresent();
    }

    static Optional<String> capturedProfile(List<Map<String,Object>> rows) throws IOException {
        String selected = null;
        for (var row : rows) {
            Object raw = row.get("_contract_profile");
            if (!(raw instanceof String hash) || !NativeModuleProfile.supported(hash)) continue;
            if (selected != null && !selected.equals(hash)) throw new IOException("Mixed native contract capture profiles");
            selected = hash;
        }
        return Optional.ofNullable(selected);
    }

    private String moduleProfile() { return NativeModuleProfile.FM27_SHA256; }

    public List<ContractSnapshot> read(long person, long expectedUid) throws IOException {
        registries.clear(); competitionIdentityGuards.clear(); capturedBytes=0;
        try {
            var result=readPerson(person,expectedUid);
            verifyRegistries();
            return result;
        } finally { registries.clear(); competitionIdentityGuards.clear(); }
    }

    /** Passive equivalent of PERSON v358(offerIndex, includeFlag=1).
     * Does not increment native reference counts or call mutating native getters.
     * This lookup does not certify the separate transfer offer as active/agreed. */
    public Optional<FutureEmploymentSnapshot> readFutureEmployment(long person, long uid, int offerIndex)
            throws IOException {
        return readFutureEmployment(person, uid, offerIndex,
                FutureEmploymentLayout.current());
    }

    Optional<FutureEmploymentSnapshot> readFutureEmployment(long person, long uid, int offerIndex,
                                                           FutureEmploymentLayout future) throws IOException {
        PlayerSnapshotValidator.validateContractLayout(layout);
        PlayerSnapshotValidator.validateFutureEmploymentLayout(future);
        if (offerIndex < 0) throw new IOException("Negative future employment offer index");
        registries.clear(); competitionIdentityGuards.clear(); capturedBytes = 0;
        try {
            byte[] identity = bytes(person, 16);
            if (Integer.toUnsignedLong(buffer(identity).getInt(layout.links().uid())) != uid || uid == 0)
                throw new IOException("Future employment PERSON identity anchor failed");
            verifyEntityIdentity(future.peopleRegistry(), person, null, identity);
            byte[] link = bytes(person + future.personRecords(), Long.BYTES);
            long headerAddress = buffer(link).getLong();
            Optional<FutureEmploymentSnapshot> result = Optional.empty();
            if (headerAddress != 0) {
                byte[] header = bytes(headerAddress, 24);
                ByteBuffer h = buffer(header);
                long begin = h.getLong(), end = h.getLong(8), capacity = h.getLong(16);
                if (begin < 0 || end < begin || capacity < end
                        || capacity > ProcessMemoryReader.MAX_USER_ADDRESS
                        || (end - begin) % 8 != 0 || (capacity - begin) % 8 != 0
                        || (end - begin) / 8 > 256 || begin == 0 && capacity != 0)
                    throw new IOException("Future employment vector bounds/count anchor failed");
                if (begin != 0) PlayerSnapshotValidator.validateContractPointer(begin, 0);
                byte[] pointers = begin == end ? new byte[0] : bytes(begin, (int) (end - begin));
                for (int i = 0; i < pointers.length; i += Long.BYTES) {
                    long address = buffer(pointers).getLong(i);
                    if (address == 0) continue;
                    byte[] raw = bytes(address, future.recordSize());
                    ByteBuffer r = buffer(raw);
                    if (r.getInt(future.recordOfferIndex()) == offerIndex) {
                        long contractAddress = r.getLong(future.recordContract());
                        var contract = readContract(contractAddress, person, uid, -1);
                        if (!"FULL".equals(contract.kind()))
                            throw new IOException("Future employment FULL contract type anchor failed");
                        byte[] core = HexFormat.of().parseHex(contract.coreHex());
                        boolean usesGameDate = (Byte.toUnsignedInt(core[future.contractDateFlags()])
                                & future.gameDateFlagMask()) != 0;
                        LocalDate endDate;
                        if (usesGameDate) {
                            byte[] date = bytes(base + future.gameDateRva(), 4);
                            LocalDate current = packedDate(date, 0);
                            endDate = LocalDate.ofYearDay(current.getYear() + 1,
                                    Math.min(current.getDayOfYear(), java.time.Year.of(current.getYear() + 1).length()));
                            unchanged(base + future.gameDateRva(), date);
                        } else endDate = packedDate(core, future.contractEnd());
                        var snapshot = new FutureEmploymentSnapshot(offerIndex,
                                Byte.toUnsignedInt(raw[future.recordState()]), HexFormat.of().formatHex(raw),
                                buffer(core).getInt(future.contractWage()), endDate, usesGameDate, contract);
                        PlayerSnapshotValidator.validateFutureEmployment(snapshot, uid, offerIndex);
                        unchanged(contractAddress, core);
                        unchanged(address, raw);
                        result = Optional.of(snapshot);
                        break; // Native v358 returns the first matching offer index, including states3/4.
                    }
                    unchanged(address, raw);
                }
                if (pointers.length != 0) unchanged(begin, pointers);
                unchanged(headerAddress, header);
            }
            unchanged(person + future.personRecords(), link);
            unchanged(person, identity);
            verifyEntityIdentity(future.peopleRegistry(), person, null, identity);
            verifyRegistries();
            return result;
        } catch (IndexOutOfBoundsException | DateTimeException invalid) {
            throw new IOException("Future employment layout/date anchor failed", invalid);
        } finally { registries.clear(); competitionIdentityGuards.clear(); }
    }

    private static LocalDate packedDate(byte[] raw, int offset) throws IOException {
        ByteBuffer b = buffer(raw);
        int ordinal = Short.toUnsignedInt(b.getShort(offset)) & 0x1FF;
        int year = Short.toUnsignedInt(b.getShort(offset + 2));
        if (year < 1900 || year > 3000 || ordinal < 1 || ordinal > java.time.Year.of(year).length())
            throw new IOException("Future employment packed date anchor failed");
        return LocalDate.ofYearDay(year, ordinal);
    }

    /** Stages the whole capture until all registry metadata has passed its final reread. */
    void captureRows(List<Map<String,Object>> rows) throws IOException {
        registries.clear(); competitionIdentityGuards.clear(); capturedBytes=0;
        try {
            List<List<ContractSnapshot>> staged=new ArrayList<>(rows.size());
            var futureExporter=new FutureTransferExporter(reader,base,com.github.fmaiassistent.memory.FutureTransferLayout.current());
            var stagedFuture=new ArrayList<FutureTransferExporter.Result>(rows.size());
            for(var row:rows) {
                if(Thread.currentThread().isInterrupted())throw new IOException("Contract capture interrupted");
                Object address = row.containsKey("record") ? row.get("record") : row.get("record_address");
                long person;
                try {
                    person = address instanceof Number number ? number.longValue() : Long.decode(String.valueOf(address));
                } catch (NumberFormatException invalid) {
                    throw new IOException("Contract capture requires a PERSON record address", invalid);
                }
                long uid=((Number)row.get("unique_id")).longValue();
                staged.add(readPerson(person,uid));
                stagedFuture.add(futureExporter.read(person,uid));
            }
            futureExporter.verify();
            verifyRegistries();
            for(int i=0;i<rows.size();i++) {
                rows.get(i).put("_contracts",staged.get(i));
                rows.get(i).put("_contract_profile",moduleProfile());
                var future=stagedFuture.get(i);var row=rows.get(i);
                row.put("_future_contracts",future.contracts());
                row.put("_future_transfer_profile",future.complete()?"AVAILABLE":"PARTIAL");
                if(row.containsKey("transfer_agreed")) {
                    row.put("transfer_agreed",future.agreed()?Boolean.TRUE:future.complete()?Boolean.FALSE:null);
                    row.put("future_transfer_club",future.clubName());
                    var first=future.contracts().isEmpty()?null:future.contracts().getFirst();
                    row.put("future_transfer_date",first==null||first.transferDate()==null?"":first.transferDate().toString());
                    row.put("future_transfer_contract_end_date",first==null||first.employment().endDate()==null?"":first.employment().endDate().toString());
                }
            }
        } finally { registries.clear(); competitionIdentityGuards.clear(); }
    }

    private List<ContractSnapshot> readPerson(long person, long expectedUid) throws IOException {
        PlayerSnapshotValidator.validateContractLayout(layout);
        var l = layout.links();
        if (reader.readU32(person + l.uid()) != expectedUid) throw new IOException("Player identity changed during contract capture");
        long primary = reader.readU64(person + l.primary());
        long additional = reader.readU64(person + l.additional());
        byte[] slots = additional == 0 ? new byte[0] : bytes(additional, l.additionalSlots() * Long.BYTES);
        List<ContractSnapshot> contracts = new ArrayList<>();
        if (primary != 0) contracts.add(readContract(primary, person, expectedUid, -1));
        for (int i = 0; i < l.additionalSlots() && additional != 0; i++) {
            long address = buffer(slots).getLong(i * Long.BYTES);
            if (address != 0) contracts.add(readContract(address, person, expectedUid, i));
        }
        if (reader.readU32(person + l.uid()) != expectedUid || reader.readU64(person + l.primary()) != primary
                || reader.readU64(person + l.additional()) != additional
                || additional != 0 && !Arrays.equals(slots, bytes(additional, slots.length))) {
            throw new IOException("Player contract links changed during capture");
        }
        PlayerSnapshotValidator.validateContracts(contracts, expectedUid);
        return List.copyOf(contracts);
    }

    private ContractSnapshot readContract(long address, long person, long uid, int slot) throws IOException {
        var links = layout.links(); var full = layout.full(); var loan = layout.loan();
        byte[] prefix = bytes(address, 16);
        long vtable = buffer(prefix).getLong();
        if (vtable < base || vtable - base >= 0x1EB40000L || buffer(prefix).getLong(links.owner()) != person)
            throw new IOException("Contract type/owner anchor failed for " + uid);
        boolean isFull = vtable == base + full.vtable(), isLoan = vtable == base + loan.vtable();
        boolean isBClub = vtable == base + layout.bClub().vtable();
        boolean isBasic = vtable == base + layout.basic().vtable();
        byte[] core = bytes(address, isFull ? full.size() : isLoan ? loan.size() : isBClub ? layout.bClub().size() : isBasic ? layout.basic().size() : 16);
        ByteBuffer b = buffer(core);
        if (b.getLong() != vtable || b.getLong(links.owner()) != person)
            throw new IOException("Contract identity changed during core read");
        List<ContractSnapshot.Term> terms = new ArrayList<>();
        Long teamUid = null, compUid = null, flagsA0 = null, flagsA8 = null, totalFee = null;
        Integer agreed = null, fee = null, threshold = null, played = null, comp = null;
        Integer condition = null, internal = null, context1 = null, context2 = null;
        String feeKind = null, auxiliaryHex = null;
        String teamOwnerKind = null; Long teamOwnerUid = null;
        if (isFull || isLoan || isBClub || isBasic) {
            long team = b.getLong(links.team());
            if (team != 0) {
                var owner = teamOwner(team);
                teamUid = owner.teamUid();
                teamOwnerKind = owner.kind(); teamOwnerUid = owner.uid();
            }
        }
        // B_CLUB virtualF8 overrides the raw getter:341C950 delegates through other contracts or returns3.
        if (isFull || isLoan) agreed = Byte.toUnsignedInt(core[links.agreed()]);
        if (isFull) {
            vector(core, full.clauses(), full.clauseStride(), "FULL_68", terms);
            vector(core, full.competitionBonuses(), full.competitionStride(), "FULL_80", terms);
            long header = b.getLong(full.awards());
            if (header != 0) {
                byte[] h = bytes(header, links.vectorCapacity() + Long.BYTES);
                vector(h, 0, full.awardStride(), "FULL_98", terms);
                unchanged(header, h);
            }
        } else if (isLoan) {
            // Reader1AA7EB0 constructs these separately from the shared block at68.
            vector(core, loan.competitionBonuses(), loan.competitionStride(), "LOAN_70", terms);
            long awardHeader = b.getLong(loan.awards());
            if (awardHeader != 0) {
                byte[] h = bytes(awardHeader, links.vectorCapacity() + Long.BYTES);
                vector(h, 0, loan.awardStride(), "LOAN_88", terms);
                unchanged(awardHeader, h);
            }
            fee = b.getInt(loan.baseFee()); threshold = (int) core[loan.threshold()]; played = (int) core[loan.played()];
            comp = b.getInt(loan.competition()); condition = Byte.toUnsignedInt(core[loan.conditionKind()]);
            internal = b.getInt(loan.internalCompetition()); context1 = b.getInt(loan.context1()); context2 = b.getInt(loan.context2());
            flagsA0 = b.getLong(loan.flagsA0()); flagsA8 = b.getLong(loan.flagsA8());
            compUid = comp < 0 ? null : indexedUid(links.competitionsRegistry(), comp);
            long shared = b.getLong(loan.shared());
            if (shared != 0) {
                byte[] h = bytes(shared, loan.sharedSize());
                auxiliaryHex = HexFormat.of().formatHex(h);
                for (var v : layout.sharedVectors()) vector(h, v.offset(), v.stride(), v.name(), terms);
                unchanged(shared, h);
            }
            int nativeTotal = fee; // Match signed32 Clff arithmetic; never include separate appearance-fee vectors.
            if (fee >= 0) for (var t : terms) {
                if (t.group().equals("SHARED_00") && t.valueRaw() > 0 && t.conditionRaw() > 0)
                    nativeTotal += t.valueRaw().intValue();
            }
            totalFee = (long) nativeTotal;
            feeKind = nativeTotal < 0 ? "ABSENT" : threshold == 100 ? "MANDATORY" : threshold >= 0 ? "AFTER_MATCHES"
                    : comp < 0 ? "OPTIONAL" : condition == 3 ? "PROMOTION_FROM_COMPETITION"
                    : condition == 6 ? "AVOID_RELEGATION_FROM_COMPETITION" : "COMPETITION_QUALIFIED";
        }
        unchanged(address, core);
        return new ContractSnapshot(isFull ? "FULL" : isLoan ? "LOAN" : isBClub ? layout.bClub().kind() : isBasic ? layout.basic().kind() : "UNSUPPORTED_" + Long.toHexString(vtable - base),
                slot, uid, teamUid, agreed, fee, totalFee, feeKind, threshold, played, comp, compUid,
                condition, internal, context1, context2, flagsA0, flagsA8, HexFormat.of().formatHex(core), auxiliaryHex, terms,
                teamOwnerKind, teamOwnerUid);
    }

    private record TeamOwnership(Long teamUid, String kind, Long uid) { }

    private TeamOwnership teamOwner(long team) throws IOException {
        var o = layout.teamOwner();
        byte[] raw = bytes(team, Math.max(16, Math.max(o.flag() + 1, o.reference() + Long.BYTES)));
        long teamUid=verifyEntityIdentity(layout.links().teamsRegistry(),team,null,raw);
        boolean club = raw[o.flag()] == 0;
        String kind = club ? "CLUB" : "NATIONAL_TEAM_CONTAINER";
        long target = buffer(raw).getLong(o.reference());
        Long uid = null;
        if (target != 0) {
            byte[] prefix = bytes(target, 16);
            long expected = base + (club ? o.clubVtable() : o.nationalContainerVtable());
            if (buffer(prefix).getLong() != expected) throw new IOException("TEAM owner type anchor failed");
            uid = verifyEntityIdentity(club ? o.clubsRegistry() : o.nationalContainersRegistry(), target, null, prefix);
            unchanged(target, prefix);
        }
        unchanged(team, raw);
        return new TeamOwnership(teamUid, kind, uid);
    }

    private void vector(byte[] core, int offset, int stride, String group, List<ContractSnapshot.Term> terms) throws IOException {
        var l = layout.links(); ByteBuffer header = buffer(core);
        long a = header.getLong(offset), z = header.getLong(offset + l.vectorEnd()), capacity = header.getLong(offset + l.vectorCapacity());
        if (z < a || capacity < z || a < 0 || capacity > ProcessMemoryReader.MAX_USER_ADDRESS
                || (z - a) % stride != 0 || (capacity - a) % stride != 0 || (z - a) / stride > MAX_ROWS
                || a == 0 && capacity != 0)
            throw new IOException("Contract vector bounds/count anchor failed: " + group);
        if (a != 0) PlayerSnapshotValidator.validateContractPointer(a, 0);
        int count = (int) ((z - a) / stride);
        byte[] data = count == 0 ? new byte[0] : bytes(a, count * stride);
        for (int i = 0; i < count; i++) {
            int at = i * stride; ByteBuffer b = buffer(data);
            Long value = null; Integer condition = null, code = null;
            if (group.equals("FULL_68")) {
                var f = layout.fullTerm(); value = (long) b.getInt(at + f.value());
                condition = (int) b.getShort(at + f.condition()); code = Byte.toUnsignedInt(data[at + f.code()]);
            } else if (group.startsWith("SHARED_") && List.of("SHARED_00", "SHARED_18", "SHARED_30", "SHARED_48", "SHARED_60").contains(group)) {
                var f = layout.sharedTerm(); value = (long) b.getInt(at + f.value());
                condition = group.equals("SHARED_00") ? (int) data[at + f.condition()] : Byte.toUnsignedInt(data[at + f.condition()]);
                if (group.equals("SHARED_00")) code = Byte.toUnsignedInt(data[at + f.code()]);
            }
            String referenceKind = null; Long referenceUid = null;
            if (group.equals("FULL_98") || group.equals("LOAN_88") || group.equals("SHARED_A8")) {
                referenceKind = "AWARD";
                long target = b.getLong(at + layout.award().reference());
                if (target != 0) {
                    byte[] prefix = bytes(target, 16);
                    if (buffer(prefix).getLong() != base + layout.award().vtable())
                        throw new IOException("Award reference type anchor failed");
                    referenceUid = verifyEntityIdentity(layout.award().registry(), target, null, prefix);
                    unchanged(target, prefix);
                }
            }
            String rawHex=HexFormat.of().formatHex(data, at, at + stride);
            ContractSnapshot.CompetitionReferences competitionReferences=null;
            if(group.equals("FULL_80")) {
                var bonusLayout=com.github.fmaiassistent.memory.CompetitionBonusLayout.current();
                PlayerSnapshotValidator.validateCompetitionBonus(rawHex,bonusLayout);
                var bonus=bonusLayout.decode(rawHex);
                competitionReferences=new ContractSnapshot.CompetitionReferences(
                        competitionUid(bonus.subjectCompetitionIndex()),competitionUid(bonus.conditionCompetitionIndex()));
            }
            terms.add(new ContractSnapshot.Term(group, i, rawHex, value, condition, code,
                    referenceKind, referenceUid,competitionReferences));
        }
        if (count > 0) unchanged(a, data);
    }

    private Long competitionUid(int index)throws IOException {
        if(index==-1)return null;
        var identity=com.github.fmaiassistent.memory.ClubFinancialCompetitionLayout.current();
        PlayerSnapshotValidator.validateClubFinancialCompetitionLayout(identity);
        var r=registry((int)identity.competitionRootRva());
        if(index<0||index>=r.count())throw new IOException("Competition bonus registry index anchor failed");
        long slot=r.begin()+(long)index*Long.BYTES;
        byte[] slotBytes=bytes(slot,Long.BYTES);long address=buffer(slotBytes).getLong();
        byte[] prefix=bytes(address,identity.competitionPrefixBytes());
        if(buffer(prefix).getLong()!=base+identity.competitionVtableRva())throw new IOException("Competition bonus COMP type anchor failed");
        long uid=verifyEntityIdentity((int)identity.competitionRootRva(),address,index,prefix);
        unchanged(address,prefix);unchanged(slot,slotBytes);
        rememberCompetitionIdentity(address,prefix);rememberCompetitionIdentity(slot,slotBytes);
        return uid;
    }

    private void rememberCompetitionIdentity(long address,byte[] raw)throws IOException {
        var previous=competitionIdentityGuards.putIfAbsent(address,raw);
        if(previous!=null&&!Arrays.equals(previous,raw))throw new IOException("Competition identity changed within capture");
    }

    private Long indexedUid(int registryRva, int index) throws IOException {
        var registry=registry(registryRva);
        if (index < 0 || index >= registry.count())
            throw new IOException("Contract entity registry index anchor failed");
        long entity = reader.readU64(registry.begin() + (long) index * Long.BYTES);
        return entityUid(registryRva, entity, index);
    }

    private Long entityUid(int registryRva, long entity, Integer expectedIndex) throws IOException {
        if (entity == 0) return null;
        byte[] prefix = bytes(entity, 16);
        long uid=verifyEntityIdentity(registryRva,entity,expectedIndex,prefix);
        unchanged(entity, prefix);
        return uid;
    }

    /** Caller must reread the captured prefix (or its containing block) before returning data. */
    private long verifyEntityIdentity(int registryRva,long entity,Integer expectedIndex,byte[] prefix) throws IOException {
        var registry=registry(registryRva);int index=buffer(prefix).getInt(layout.links().index());
        if (index < 0 || index >= registry.count()
                || expectedIndex != null && index != expectedIndex || reader.readU64(registry.begin() + (long) index * Long.BYTES) != entity)
            throw new IOException("Contract relationship identity anchor failed");
        long uid = Integer.toUnsignedLong(buffer(prefix).getInt(layout.links().uid()));
        if (uid == 0) throw new IOException("Contract relationship has zero UID");
        return uid;
    }

    private record RegistrySnapshot(int rva,long descriptor,long header,byte[] rootBytes,byte[] headerBytes,
                                    byte[] boundsBytes,long begin,long count) { }

    private RegistrySnapshot registry(int rva) throws IOException {
        var found=registries.get(rva);if(found!=null)return found;
        byte[] root=bytes(base+rva,Long.BYTES);long descriptor=buffer(root).getLong();
        byte[] headerPointer=bytes(descriptor+layout.links().registryHeader(),Long.BYTES);
        long header=buffer(headerPointer).getLong();
        byte[] bounds=bytes(header,Math.max(Long.BYTES,layout.links().vectorEnd()+Long.BYTES));
        long begin=buffer(bounds).getLong(),end=buffer(bounds).getLong(layout.links().vectorEnd());
        if(begin<0||end<begin||end>ProcessMemoryReader.MAX_USER_ADDRESS||(end-begin)%Long.BYTES!=0||begin==0&&end!=0)
            throw new IOException("Contract registry bounds anchor failed");
        if(begin!=0)PlayerSnapshotValidator.validateContractPointer(begin,0);
        var result=new RegistrySnapshot(rva,descriptor,header,root,headerPointer,bounds,begin,(end-begin)/Long.BYTES);
        registries.put(rva,result);return result;
    }

    private void verifyRegistries() throws IOException {
        for(var guard:competitionIdentityGuards.entrySet())unchanged(guard.getKey(),guard.getValue());
        for(var r:registries.values()) {
            unchanged(base+r.rva(),r.rootBytes());
            unchanged(r.descriptor()+layout.links().registryHeader(),r.headerBytes());
            unchanged(r.header(),r.boundsBytes());
        }
    }

    private byte[] bytes(long address, int size) throws IOException {
        PlayerSnapshotValidator.validateContractPointer(address, size);
        capturedBytes += size;
        if (capturedBytes > MAX_CAPTURE_BYTES) throw new IOException("Contract snapshot byte budget exceeded");
        byte[] result = reader.readBytes(address, size);
        if (result.length != size) throw new IOException("Short contract read");
        return result;
    }
    private void unchanged(long address, byte[] original) throws IOException {
        if (!Arrays.equals(original, bytes(address, original.length))) throw new IOException("Contract data changed during capture");
    }
    private static ByteBuffer buffer(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
}
