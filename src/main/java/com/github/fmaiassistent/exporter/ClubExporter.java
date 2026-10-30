package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.linux.FmMemoryStrings;
import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.memory.ClubRecordLayout;
import com.github.fmaiassistent.memory.ClubIdentityLayout;
import com.github.fmaiassistent.domain.NativeClubSnapshot;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.ProcessReaders;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

public class ClubExporter {
    public static final List<String> FIELD_NAMES = List.of(
            "sourceAddress", "uniqueId", "name", "gender", "competition", "reputation", "nation", "balance", "transferBudget", "payrollBudget", "currentWagesIncludingBonuses",
            "trainingFacilities", "youthFacilities", "youthCoaching", "youthRecruitment", "corporateFacilities");

    private static final List<Integer> CLUB_FINANCE_MARKERS = List.of(0x07F8, 0x0CC8);

    private ClubRecordLayout recordLayout = ClubRecordLayout.current();

    public ClubExporter() {
    }

    public ClubExporter(ClubRecordLayout recordLayout) {
        this.recordLayout = recordLayout;
    }

    /**
     * Record layout for the next export. Set once per load; safe because only
     * one refresh runs at a time.
     */
    public void setRecordLayout(ClubRecordLayout recordLayout) {
        this.recordLayout = recordLayout;
    }

    public ExportResult exportAllClubs(int pid, int build, Long gamePluginBase) throws IOException {
        try (ProcessMemoryReader reader = ProcessReaders.open(pid)) {
            ClubIdentitySnapshot identities=null;
            String identityProfile=null;
            if(reader.platform()==ProcessMemoryReader.Platform.WINDOWS) {
                long base=gamePluginBase==null?FmOffsets.findGamePluginBase(reader):gamePluginBase;
                String override=System.getProperty(ContractExporter.MODULE_PROPERTY);
                Path module=override==null||override.isBlank()?reader.maps().stream()
                        .filter(r->r.contains(base,1)&&!r.path().isBlank()).map(r->Path.of(r.path())).findFirst().orElse(null):Path.of(override);
                identityProfile=module==null?null:com.github.fmaiassistent.memory.NativeModuleProfile.identify(module).orElse(null);
                if(identityProfile!=null) {
                    recordLayout=ClubRecordLayout.current();
                    identities=new ClubIdentitySnapshot(reader,base,ClubIdentityLayout.current(),
                            com.github.fmaiassistent.memory.ClubUefaCoefficientLayout.current(),
                            com.github.fmaiassistent.memory.ClubFinancialLoanLayout.current(),
                            com.github.fmaiassistent.memory.ClubFinancialLedgerLayout.current(),
                            com.github.fmaiassistent.memory.ClubFinancialCompetitionLayout.current(),
                            com.github.fmaiassistent.memory.CompetitionNameLayout.current(),
                            com.github.fmaiassistent.memory.ClubFinancialHistoryLayout.current());
                }
            }
            FmOffsets.Bounds bounds = FmOffsets.tableBounds(reader, build, gamePluginBase, "TeamOffset");
            if(bounds.count()>1_000_000)throw new IOException("TEAM table exceeds bounded club capture limit");
            PointerTable teams = PointerTable.read(reader, bounds, "Team");
            Map<String, Map<String, Object>> byClub = new LinkedHashMap<>();
            for (int index = 0; index < teams.size(); index++) {
                long team = teams.pointerAt(index);
                if (team <= 0 || team > ProcessMemoryReader.MAX_USER_ADDRESS) {
                    continue;
                }
                try {
                    Map<String, Object> row = identities==null?decodeTeamClub(reader,team,null,null,null):decodeNativeTeamClub(reader,team,index,identities);
                    if (row.isEmpty()) {
                        continue;
                    }
                    long club = ((Number) row.get("sourceAddress")).longValue();
                    String key = club + ":" + row.get("gender") + ":" + row.get("competition");
                    Map<String, Object> previous = byClub.get(key);
                    if (previous == null || score(row) > score(previous)) {
                        byClub.put(key, row);
                    }
                } catch (PlayerSnapshotValidator.ClubIdentityChangedException changed) {
                    throw changed;
                } catch (IOException | RuntimeException ignored) {
                    if(identities!=null)throw new IOException("Typed club view capture failed",ignored);
                }
            }
            List<Map<String, Object>> rows = new ArrayList<>(byClub.values());
            rows.sort(Comparator.comparing(row -> String.valueOf(row.get("name")).toLowerCase()));
            if(identities!=null) {
                identities.verify();
                if(!bounds.equals(FmOffsets.tableBounds(reader,build,gamePluginBase,"TeamOffset"))
                        ||teams.bytes().length>0&&!Arrays.equals(teams.bytes(),reader.readBytes(bounds.start(),teams.bytes().length)))
                    throw new PlayerSnapshotValidator.ClubIdentityChangedException("TEAM registry changed during club capture");
            }
            return new ExportResult(rows,identities==null?List.of():identities.records(),identities==null?null:identityProfile,
                    identities==null?null:identities.competitionNames());
        }
    }

    private Map<String,Object> decodeNativeTeamClub(ProcessMemoryReader reader,long team,int index,ClubIdentitySnapshot identities)throws IOException {
        var identity=identities.layout();
        byte[] prefix=reader.readBytes(team,16);PlayerSnapshotValidator.validateClubTeamIdentity(index,prefix,identities.base(),identity);
        int size=Math.toIntExact(Math.max(16,Math.max(identity.teamOwnerFlag()+1,Math.max(recordLayout.teamClubRel()+8,
                Math.max(recordLayout.teamCompetitionRel()+8,recordLayout.teamReputationRel()+2)))));
        if(size>512)throw new PlayerSnapshotValidator.ClubIdentityChangedException("TEAM field range anchor failed");
        byte[] raw=reader.readBytes(team,size);
        if(!Arrays.equals(prefix,Arrays.copyOf(raw,16)))throw new PlayerSnapshotValidator.ClubIdentityChangedException("TEAM identity changed");
        try {
            if(raw[identity.teamOwnerFlag()]!=0)return Map.of(); // Separate national-container namespace.
            long owner=buffer(raw).getLong((int)recordLayout.teamClubRel());if(owner==0)return Map.of();
            return decodeTeamClub(reader,team,identities.club(owner),raw,identities.base());
        } finally {
            byte[] after=reader.readBytes(team,size);
            if(!Arrays.equals(prefix,Arrays.copyOf(after,16))||raw[identity.teamOwnerFlag()]!=after[identity.teamOwnerFlag()]
                    ||buffer(raw).getLong((int)recordLayout.teamClubRel())!=buffer(after).getLong((int)recordLayout.teamClubRel())
                    ||buffer(raw).getLong((int)recordLayout.teamCompetitionRel())!=buffer(after).getLong((int)recordLayout.teamCompetitionRel())
                    ||buffer(raw).getShort((int)recordLayout.teamReputationRel())!=buffer(after).getShort((int)recordLayout.teamReputationRel()))
                throw new PlayerSnapshotValidator.ClubIdentityChangedException("TEAM context changed during club capture");
        }
    }

    private Map<String, Object> decodeTeamClub(ProcessMemoryReader reader, long team,NativeClubSnapshot nativeClub,byte[] teamRaw,Long verifiedModuleBase) throws IOException {
        var clubOpt = nativeClub==null?reader.qwordOrNull(team + recordLayout.teamClubRel()):java.util.Optional.of(nativeClub.sourceAddress());
        if (clubOpt.isEmpty()) {
            return Map.of();
        }
        long club = clubOpt.get();
        String name = nativeClub==null?FmMemoryStrings.clubDisplayName(reader, club).orElse(""):java.util.Objects.requireNonNullElse(nativeClub.name(),"");
        if (name.isBlank()) {
            return Map.of();
        }
        int reputation = teamRaw==null?reader.readU16(team + recordLayout.teamReputationRel()):Short.toUnsignedInt(buffer(teamRaw).getShort((int)recordLayout.teamReputationRel()));
        if (reputation <= 0 || reputation > 10000) {
            return Map.of();
        }
        long nativeCompetition=teamRaw==null?0:buffer(teamRaw).getLong((int)recordLayout.teamCompetitionRel());
        var competitionOpt = teamRaw==null?reader.qwordOrNull(team + recordLayout.teamCompetitionRel()):
                nativeCompetition>0&&nativeCompetition<=ProcessMemoryReader.MAX_USER_ADDRESS?java.util.Optional.of(nativeCompetition):java.util.Optional.<Long>empty();
        if (competitionOpt.isEmpty()) {
            return Map.of();
        }
        long competitionAddress = competitionOpt.get();
        String competition = FmMemoryStrings.competitionDisplayName(reader, competitionAddress).orElse("");
        if (competition.isBlank()) {
            return Map.of();
        }
        String nation = FmMemoryStrings.clubNation(reader, club).orElse("");
        String gender = reader.readU8(competitionAddress + recordLayout.competitionGenderFlagRel()) == 1 ? "female" : "male";
        if ("female".equals(gender) && !name.endsWith(" (W)")) {
            name += " (W)";
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sourceAddress", club);
        row.put("uniqueId",nativeClub==null?null:nativeClub.uniqueId());
        row.put("_competition_address", competitionAddress);
        row.put("name", name);
        row.put("gender", gender);
        row.put("competition", competition);
        row.put("reputation", reputation);
        row.put("nation", nation);
        Finance finance = verifiedModuleBase == null ? readFinance(reader, club)
                : readFinance(reader, club, verifiedModuleBase);
        row.put("balance", finance.balance());
        row.put("transferBudget", finance.transferBudget());
        row.put("payrollBudget", finance.payrollBudget());
        row.put("currentWagesIncludingBonuses",finance.currentWagesIncludingBonuses());
        Facilities facilities = verifiedModuleBase==null?readFacilities(reader, club):readFacilities(reader, club,verifiedModuleBase);
        row.put("trainingFacilities", facilities.training());
        row.put("youthFacilities", facilities.youth());
        row.put("youthCoaching", facilities.coaching());
        row.put("youthRecruitment", facilities.recruitment());
        row.put("corporateFacilities", facilities.corporate());
        return row;
    }

    private static int score(Map<String, Object> row) {
        int score = ((Number) row.getOrDefault("reputation", 0)).intValue();
        if (!String.valueOf(row.getOrDefault("competition", "")).isBlank()) {
            score += 10000;
        }
        return score;
    }

    Finance readFinance(ProcessMemoryReader reader, long club, long verifiedModuleBase) throws IOException {
        return readFinance(reader, club, verifiedModuleBase, com.github.fmaiassistent.memory.ClubFinanceLayout.current());
    }

    Finance readFinance(ProcessMemoryReader reader, long club, long verifiedModuleBase,
            com.github.fmaiassistent.memory.ClubFinanceLayout layout) throws IOException {
        PlayerSnapshotValidator.validateClubFinanceLayout(recordLayout, layout);
        long child = reader.readU64(club + recordLayout.clubFinanceBlockRel());
        if (child == 0) return new Finance(null, null, null);
        if (verifiedModuleBase < 0x10000 || child < 0x10000
                || child > ProcessMemoryReader.MAX_USER_ADDRESS - layout.fullBytes())
            throw new PlayerSnapshotValidator.ClubIdentityChangedException("CLUB_FINANCE pointer/range anchor failed");
        byte[] prefix = reader.readBytes(child, Math.max(Long.BYTES,layout.ownerRel() + Long.BYTES));
        long vtable = buffer(prefix).getLong();
        boolean full = vtable == verifiedModuleBase + layout.fullVtableRva();
        if ((!full && vtable != verifiedModuleBase + layout.baseVtableRva())
                || layout.ownerRel()>=0&&buffer(prefix).getLong(layout.ownerRel()) != club)
            throw new PlayerSnapshotValidator.ClubIdentityChangedException("CLUB_FINANCE native type/owner anchor failed");
        int size = full ? layout.fullBytes() : layout.baseBytes();
        byte[] raw = reader.readBytes(child, size);
        if (!Arrays.equals(prefix, Arrays.copyOf(raw, prefix.length)))
            throw new PlayerSnapshotValidator.ClubIdentityChangedException("CLUB_FINANCE type/owner changed");
        long balance = buffer(raw).getInt(Math.toIntExact(recordLayout.clubBalanceRel()));
        Long transfer = full ? (long) buffer(raw).getInt(Math.toIntExact(recordLayout.clubTransferBudgetRel())) : null;
        Long payroll = full ? (long) buffer(raw).getInt(Math.toIntExact(recordLayout.clubPayrollBudgetRel())) : null;
        Long currentWages = null;
        if (full) {
            int cached = buffer(raw).getInt(layout.currentWagesRel());
            // 163CF90 would recompute <=-1001 through game-thread logic. Read-only exports leave it unavailable.
            if (cached > layout.firstUncachedWage()) currentWages = (long) cached;
        }
        if (reader.readU64(club + recordLayout.clubFinanceBlockRel()) != child
                || !Arrays.equals(raw, reader.readBytes(child, size)))
            throw new PlayerSnapshotValidator.ClubIdentityChangedException("CLUB_FINANCE changed during capture");
        return new Finance(balance, transfer, payroll, currentWages);
    }

    private Facilities readFacilities(ProcessMemoryReader reader,long club,long base)throws IOException {
        var finance=com.github.fmaiassistent.memory.ClubFinanceLayout.current();
        PlayerSnapshotValidator.validateClubFinanceLayout(recordLayout,finance);
        long data=reader.readU64(club+recordLayout.clubFacilitiesBlockRel());
        int training=0,youth=0,coaching=0,recruitment=0,corporate=0;
        if(data!=0) {
            training=facilityRating(reader,data+recordLayout.clubTrainingFacilitiesRel());
            youth=facilityRating(reader,data+recordLayout.clubYouthFacilitiesRel());
            coaching=facilityRating(reader,data+recordLayout.clubYouthCoachingRel());
            recruitment=facilityRating(reader,data+recordLayout.clubYouthRecruitmentRel());
        }
        long child=reader.readU64(club+recordLayout.clubFinanceBlockRel());
        if(child!=0&&reader.readU64(child)==base+finance.fullVtableRva())
            corporate=facilityRating(reader,child+recordLayout.clubCorporateFacilitiesRel());
        if(reader.readU64(club+recordLayout.clubFacilitiesBlockRel())!=data
                ||reader.readU64(club+recordLayout.clubFinanceBlockRel())!=child)
            throw new PlayerSnapshotValidator.ClubIdentityChangedException("CLUB facilities relationship changed during capture");
        return new Facilities(training,youth,coaching,recruitment,corporate);
    }

    Finance readFinance(ProcessMemoryReader reader, long club) throws IOException {
        var extraOpt = reader.qwordOrNull(club + recordLayout.clubFinanceBlockRel());
        if (extraOpt.isEmpty()) {
            return new Finance(0L, 0L, 0L);
        }
        long extra = extraOpt.get();
        if (!CLUB_FINANCE_MARKERS.contains(reader.readU16(extra))) {
            return new Finance(0L, 0L, 0L);
        }
        long balanceRaw = reader.readI32(extra + recordLayout.clubBalanceRel());
        long balance = roundToNearest(balanceRaw, balanceRoundingStep(balanceRaw));
        long transferBudgetRaw = reader.readI32(extra + recordLayout.clubTransferBudgetRel());
        long transferBudget = roundToNearest(transferBudgetRaw, transferRoundingStep(transferBudgetRaw));
        long payrollRaw = reader.readI32(extra + recordLayout.clubPayrollBudgetRel());
        long payrollBudget = roundToNearest(payrollRaw, payrollRoundingStep(payrollRaw));
        return new Finance(balance, transferBudget, payrollBudget);
    }

    Facilities readFacilities(ProcessMemoryReader reader, long club) throws IOException {
        int training = 0;
        int youth = 0;
        int coaching = 0;
        int recruitment = 0;
        var facilitiesOpt = reader.qwordOrNull(club + recordLayout.clubFacilitiesBlockRel());
        if (facilitiesOpt.isPresent()) {
            long facilities = facilitiesOpt.get();
            training = facilityRating(reader, facilities + recordLayout.clubTrainingFacilitiesRel());
            youth = facilityRating(reader, facilities + recordLayout.clubYouthFacilitiesRel());
            coaching = facilityRating(reader, facilities + recordLayout.clubYouthCoachingRel());
            recruitment = facilityRating(reader, facilities + recordLayout.clubYouthRecruitmentRel());
        }

        int corporate = 0;
        var extraOpt = reader.qwordOrNull(club + recordLayout.clubFinanceBlockRel());
        if (extraOpt.isPresent()) {
            long extra = extraOpt.get();
            if (CLUB_FINANCE_MARKERS.contains(reader.readU16(extra))) {
                corporate = facilityRating(reader, extra + recordLayout.clubCorporateFacilitiesRel());
            }
        }
        return new Facilities(training, youth, coaching, recruitment, corporate);
    }

    private static int facilityRating(ProcessMemoryReader reader, long address) throws IOException {
        int rating = reader.readU8(address);
        return rating <= 20 ? rating : 0;
    }

    private static long balanceRoundingStep(long value) {
        long abs = Math.abs(value);
        if (abs >= 10_000_000L) {
            return 1_000_000L;
        }
        if (abs >= 1_000_000L) {
            return 100_000L;
        }
        if (abs >= 100_000L) {
            return 25_000L;
        }
        return 1_000L;
    }

    private static long transferRoundingStep(long value) {
        long abs = Math.abs(value);
        if (abs >= 50_000_000L) {
            return 1_000_000L;
        }
        if (abs >= 1_000_000L) {
            return 250_000L;
        }
        if (abs >= 500_000L) {
            return 1_000L;
        }
        if (abs >= 100_000L) {
            return 25_000L;
        }
        return 250L;
    }

    private static long payrollRoundingStep(long value) {
        long abs = Math.abs(value);
        if (abs >= 5_000_000L) {
            return 250_000L;
        }
        if (abs >= 1_000_000L) {
            return 100_000L;
        }
        if (abs >= 100_000L) {
            return 5_000L;
        }
        if (abs >= 50_000L) {
            return 1_000L;
        }
        return 250L;
    }

    private static long roundToNearest(long value, long step) {
        if (value == 0 || step <= 0) {
            return value;
        }
        return Math.round(value / (double) step) * step;
    }

    public record ExportResult(List<Map<String, Object>> rows,List<NativeClubSnapshot> nativeRecords,String identityProfile,
                               List<com.github.fmaiassistent.domain.NativeCompetitionNameSnapshot> competitionNames) {
        public ExportResult {rows=List.copyOf(rows);nativeRecords=List.copyOf(nativeRecords);competitionNames=competitionNames==null?null:List.copyOf(competitionNames);}
        /** Null references mean legacy/unavailable capture, distinct from an observed empty set. */
        public ExportResult(List<Map<String,Object>> rows,List<NativeClubSnapshot> nativeRecords,String identityProfile){this(rows,nativeRecords,identityProfile,null);}
        public ExportResult(List<Map<String,Object>> rows){this(rows,List.of(),null);}
    }

    private static ByteBuffer buffer(byte[] raw){return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);}

    record Finance(Long balance, Long transferBudget, Long payrollBudget, Long currentWagesIncludingBonuses) {
        Finance(Long balance,Long transferBudget,Long payrollBudget){this(balance,transferBudget,payrollBudget,null);}
    }

    record Facilities(int training, int youth, int coaching, int recruitment, int corporate) {
    }
}
