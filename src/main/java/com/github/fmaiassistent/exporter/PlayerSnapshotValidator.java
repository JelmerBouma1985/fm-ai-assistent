package com.github.fmaiassistent.exporter;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Structural sanity checks on decoded player rows before they replace the
 * snapshot. RAM values are exact, so tight ranges with zero tolerance apply:
 * any single violation means the memory layout drifted and the load aborts,
 * leaving the previous snapshot in place. Blank values are skipped because
 * several fields are legitimately absent for some records.
 */
public final class PlayerSnapshotValidator {
    private static final long MIN_PLAUSIBLE_CLUB_BUDGET = -1_000_000_000L;

    private PlayerSnapshotValidator() {
    }

    /** Native RTTI and the human PERSON getter independently anchor the complete-object displacements. */
    public static void validatePersonSubtypeLayout(
            com.github.fmaiassistent.memory.PersonIdentityLayout.Subtypes layout)
            throws PersonIdentityChangedException {
        if (layout == null || layout.staff() != 0x100 || layout.player() != 0x288
                || layout.playerStaff() != 0x380
                || layout.humanManager() != 0x440) {
            throw new PersonIdentityChangedException("PERSON subtype native displacement anchor failed");
        }
    }

    /** A valid pointer and vtable do not identify a PERSON after FM reuses its allocation. */
    public static long validatePersonIdentity(
            int expectedIndex, byte[] prefix, com.github.fmaiassistent.memory.PersonIdentityLayout layout)
            throws PersonIdentityChangedException {
        if (expectedIndex < 0 || layout.prefixBytes() < Long.BYTES || layout.prefixBytes() > 64
                || prefix.length != layout.prefixBytes()
                || layout.indexRel() < Long.BYTES || layout.indexRel() > prefix.length - Integer.BYTES
                || layout.uniqueIdRel() < Long.BYTES || layout.uniqueIdRel() > prefix.length - Integer.BYTES
                || layout.uniqueIdRel() != layout.indexRel() + Integer.BYTES) {
            throw new PersonIdentityChangedException("PERSON identity prefix layout is invalid");
        }
        var bytes = java.nio.ByteBuffer.wrap(prefix).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int nativeIndex = bytes.getInt(layout.indexRel());
        long uid = Integer.toUnsignedLong(bytes.getInt(layout.uniqueIdRel()));
        if (nativeIndex != expectedIndex || uid == 0) {
            throw new PersonIdentityChangedException(
                    "PERSON identity changed: table index=" + expectedIndex + ", native index=" + nativeIndex);
        }
        return uid;
    }

    /** Must escape worker skip-record handling so the partially read export is discarded. */
    public static final class PersonIdentityChangedException extends IOException {
        public PersonIdentityChangedException(String message) {
            super(message);
        }
    }

    /** Exact-build native member/range anchors, before publishing optional management profiles.
     * Offsets come from the native NPAT index loop and retained64-byte typed member captures.
     * A shifted payload can contain plausible values; its structural displacement must still fail.
     */
    public static void validateNonPlayingProfile(byte[] member,long base,
            com.github.fmaiassistent.memory.NonPlayingProfileLayout layout)throws IOException {
        validateNonPlayingProfileLayout(layout);
        if(member.length!=layout.memberBytes()
                ||java.nio.ByteBuffer.wrap(member).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong()!=base+layout.memberVtableRva())
            throw new IOException("NON_PLAYING_DATA type/range/native displacement anchor failed");
    }

    public static void validateNonPlayingProfileLayout(
            com.github.fmaiassistent.memory.NonPlayingProfileLayout layout)throws IOException {
        var known=com.github.fmaiassistent.memory.NonPlayingProfileLayout.current();
        if((!known.equals(layout))
                ||layout.profileOffset()<Long.BYTES||layout.profileBytes()!=52
                ||layout.profileOffset()>layout.memberBytes()-layout.profileBytes())
            throw new IOException("NON_PLAYING_DATA range/native displacement anchor failed");
    }

    /** Must escape the club exporter's legacy per-row skip handling. */
    public static final class ClubIdentityChangedException extends IOException {
        public ClubIdentityChangedException(String message){super(message);}
    }

    /** Independent16D2510/86A30 access/range anchors; reject plausible bytes at shifted displacements. */
    public static void validateClubFinancialLedgerLayout(
            com.github.fmaiassistent.memory.ClubFinancialLedgerLayout l)throws IOException {
        if(l.financeRel()!=0x150||l.baseVtableRva()!=0x4CF07F8L||l.fullVtableRva()!=0x4CF0CC8L
                ||l.ownerRel()!=-1||l.fullBytes()!=0x8B0||l.blocksRel()!=0x248||l.blockStride()!=0x118||l.blockCount()!=5
                ||l.recordRel()!=0x30||l.recordBytes()!=0xE4||l.tailRel()!=0x114||l.tailBytes()!=4
                ||l.slotCount()!=57||l.slotBytes()!=4||l.clockRva()!=0x56AD378L||l.clockBytes()!=4
                ||l.blocksRel()+l.blockWindowBytes()>l.fullBytes()||l.recordBytes()!=l.slotCount()*l.slotBytes()
                ||l.recordRel()+l.recordBytes()!=l.tailRel()||l.tailRel()+l.tailBytes()!=l.blockStride())
            throw new IOException("Financial ledger layout anchor failed");
    }
    public static void validateClubFinancialLedger(byte[] raw,int clock,
            com.github.fmaiassistent.memory.ClubFinancialLedgerLayout l)throws IOException {
        validateClubFinancialLedgerLayout(l);
        try{l.decode(raw,clock);}catch(IllegalArgumentException|IndexOutOfBoundsException invalid){
            throw new IOException("Financial ledger record/clock range anchor failed",invalid);
        }
    }

    /** Independent native writer10A2CA50/10AEA080 and COMP prefix/registry anchors. */
    public static void validateClubFinancialCompetitionLayout(
            com.github.fmaiassistent.memory.ClubFinancialCompetitionLayout l)throws IOException {
        validateClubFinancialLedgerLayout(l.ledger());
        if(l.competitionRootRva()!=0x57036E8L||l.registryHeaderRel()!=0x80||l.competitionVtableRva()!=0x4CDFAD8L
                ||l.competitionPrefixBytes()!=16||l.competitionIndexRel()!=8||l.competitionUidRel()!=12
                ||l.vectorCount()!=2||l.headerBytes()!=24||l.beginRel()!=0||l.endRel()!=8||l.capacityRel()!=16
                ||l.entryBytes()!=16||l.competitionRel()!=0||l.amountRel()!=8||l.suffixRel()!=12||l.suffixBytes()!=4
                ||l.maximumEntries()!=512||l.suffixRel()+l.suffixBytes()!=l.entryBytes()
                ||l.vectorCount()*l.headerBytes()!=l.ledger().recordRel())
            throw new IOException("Financial competition layout anchor failed");
    }

    /** F9DDC6 ADD RDI,40; F9DB70 ADD RDI,48; F95220 i32 length /+4 byte access. */
    public static void validateCompetitionNameLayout(com.github.fmaiassistent.memory.CompetitionNameLayout l)throws IOException {
        if(l.nameRel()!=0x40||l.shortNameRel()!=0x48||l.lengthRel()!=0||l.dataRel()!=4||l.lengthBytes()!=4
                ||l.maximumBytes()!=1024||l.nameRel()+Long.BYTES!=l.shortNameRel()
                ||l.lengthRel()+l.lengthBytes()!=l.dataRel())throw new IOException("Competition name layout anchor failed");
    }

    /** Native 170C403 reads subject+14; 1709790/BDC760 distinguish condition index and stage UID keys. */
    public static void validateCompetitionBonusLayout(com.github.fmaiassistent.memory.CompetitionBonusLayout l)throws IOException {
        if(l.rowBytes()!=32||l.dateRel()!=0||l.conditionCompetitionRel()!=4||l.stageUidRel()!=8
                ||l.secondaryStageUidRel()!=12||l.conditionKindRel()!=16||l.subjectCompetitionRel()!=20
                ||l.amountRel()!=24||l.minimumGamesRel()!=28||l.stateRel()!=30||l.opaqueRel()!=31
                ||l.subjectCompetitionRel()+Integer.BYTES!=l.amountRel()
                ||l.minimumGamesRel()+Short.BYTES!=l.stateRel()||l.opaqueRel()+1!=l.rowBytes())
            throw new IOException("Competition bonus layout anchor failed");
    }

    public static void validateCompetitionBonus(String rawHex,
            com.github.fmaiassistent.memory.CompetitionBonusLayout l)throws IOException {
        validateCompetitionBonusLayout(l);
        try{l.decode(rawHex);}catch(IllegalArgumentException|IndexOutOfBoundsException invalid){
            throw new IOException("Competition bonus row range anchor failed",invalid);}
    }

    /** Checkpoint54:43 UID/index/registry-checked AWARD prefixes and86 independently captured name strings. */
    public static void validateAwardNameLayout(com.github.fmaiassistent.memory.AwardNameLayout l)throws IOException {
        boolean known=l.equals(com.github.fmaiassistent.memory.AwardNameLayout.current());
        if(!known||l.registryHeaderRel()!=0x80||l.prefixBytes()!=64
                ||l.indexRel()!=8||l.uidRel()!=12||l.nameRel()!=0x30||l.shortNameRel()!=0x38
                ||l.lengthRel()!=0||l.dataRel()!=4||l.maximumBytes()!=1024||l.shortNameRel()+8!=l.prefixBytes()
                ||l.rowBytes()!=16||l.amountRel()!=8||l.stateRel()!=12||l.amountRel()+4!=l.stateRel())
            throw new IOException("Award name layout anchor failed");
    }

    /** A9BEB/A9DE2/A9E75: native ring displacements;16640C0/1664A90: exact field widths. */
    public static void validateClubFinancialHistoryLayout(com.github.fmaiassistent.memory.ClubFinancialHistoryLayout l)throws IOException {
        validateClubFinancialLedgerLayout(l.ledger());
        if(l.summaryRel()!=0x38||l.baseRingRel()!=0x228||l.headerBytes()!=0x1F0||l.countRel()!=0
                ||l.pointersRel()!=8||l.pointerBytes()!=8||l.cursorRel()!=0x1E8||l.capacity()!=60
                ||l.summaryBytes()!=48||l.baseBytes()!=228||l.balanceRel()!=0||l.remainingRel()!=8
                ||l.wagesRel()!=16||l.scoutingRel()!=136||l.pointersRel()+l.capacity()*l.pointerBytes()!=l.cursorRel()
                ||l.summaryRel()+l.headerBytes()!=l.baseRingRel()||l.baseBytes()!=l.ledger().recordBytes())
            throw new IOException("Financial history layout anchor failed");
    }
    public static void validateClubFinancialHistory(byte[] raw,int clock,
            com.github.fmaiassistent.memory.ClubFinancialHistoryLayout l)throws IOException {
        validateClubFinancialHistoryLayout(l);
        try{l.decode(raw,clock);}catch(IllegalArgumentException|IndexOutOfBoundsException invalid){
            throw new IOException("Financial history record/clock range anchor failed",invalid);}
    }

    /**16E9450 allocates1C; fields18/19/1A, vector48 and date/amount extents are native anchors. */
    public static void validateClubFinancialLoanLayout(com.github.fmaiassistent.memory.ClubFinancialLoanLayout layout)throws IOException {
        if((!com.github.fmaiassistent.memory.ClubFinancialLoanLayout.current().equals(layout))
                ||layout.unassignedRel()+1!=layout.recordBytes()||layout.kindRel()+4!=layout.recordBytes()
                ||layout.startRel()+Integer.BYTES>layout.kindRel()||layout.clockBytes()!=Integer.BYTES
                ||layout.headerBytes()!=3*Long.BYTES||layout.pointerStride()!=Long.BYTES)
            throw new IOException("Financial loan layout anchor failed");
    }
    public static void validateClubFinancialLoans(byte[] raw,int clock,
            com.github.fmaiassistent.memory.ClubFinancialLoanLayout layout)throws IOException {
        validateClubFinancialLoanLayout(layout);
        try{layout.decode(raw,clock);}
        catch(IllegalArgumentException|IndexOutOfBoundsException invalid){throw new IOException("Financial loan range anchor failed",invalid);}
    }

    /** EB55F0/2714160/1C2180/2748080 offsets and extents; reject drift before any new read. */
    public static void validateClubUefaLayout(com.github.fmaiassistent.memory.ClubUefaCoefficientLayout layout) throws IOException {
        if ((!com.github.fmaiassistent.memory.ClubUefaCoefficientLayout.current().equals(layout))
                || layout.historyRel() + layout.capacity() * layout.historyStride() > layout.cursorRel()
                || layout.currentPointsRel() + Integer.BYTES > layout.objectBytes()
                || layout.ruleVersionBytes() != Short.BYTES)
            throw new IOException("UEFA coefficient layout anchor failed");
    }

    public static void validateClubUefaCoefficient(byte[] raw, int ruleVersion,
            com.github.fmaiassistent.memory.ClubUefaCoefficientLayout layout) throws IOException {
        validateClubUefaLayout(layout);
        try { layout.decode(raw, ruleVersion); }
        catch (IllegalArgumentException | IndexOutOfBoundsException invalid) {
            throw new IOException("UEFA coefficient range anchor failed", invalid);
        }
    }

    /** Native financial getter/typed allocation anchors; reject drift before reading a child. */
    public static void validateClubFinanceLayout(com.github.fmaiassistent.memory.ClubRecordLayout record,
            com.github.fmaiassistent.memory.ClubFinanceLayout finance) throws IOException {
        var known = com.github.fmaiassistent.memory.ClubRecordLayout.current();
        if (!known.equals(record)
                ||!(com.github.fmaiassistent.memory.ClubFinanceLayout.current()).equals(finance)
                || record.clubFinanceBlockRel() != known.clubFinanceBlockRel()
                || record.clubBalanceRel() != known.clubBalanceRel()
                || record.clubTransferBudgetRel() != known.clubTransferBudgetRel()
                || record.clubPayrollBudgetRel() != known.clubPayrollBudgetRel()
                || record.clubCorporateFacilitiesRel() != known.clubCorporateFacilitiesRel()
                || record.clubFacilitiesBlockRel() != known.clubFacilitiesBlockRel()
                || record.clubTrainingFacilitiesRel() != known.clubTrainingFacilitiesRel()
                || record.clubYouthFacilitiesRel() != known.clubYouthFacilitiesRel()
                || record.clubYouthCoachingRel() != known.clubYouthCoachingRel()
                || record.clubYouthRecruitmentRel() != known.clubYouthRecruitmentRel()
                || record.clubBalanceRel() < finance.ownerRel() + Long.BYTES
                || record.clubBalanceRel() > finance.baseBytes() - Integer.BYTES
                || record.clubTransferBudgetRel() > finance.fullBytes() - Integer.BYTES
                || record.clubPayrollBudgetRel() > finance.fullBytes() - Integer.BYTES
                || finance.currentWagesRel() < finance.ownerRel() + Long.BYTES
                || finance.currentWagesRel() > finance.fullBytes() - Integer.BYTES
                || finance.fullBytes() > 4096) {
            throw new ClubIdentityChangedException("CLUB_FINANCE type/extent/native displacement anchor failed");
        }
    }

    public static long validateClubIdentity(int expectedIndex,byte[] prefix,long base,
            com.github.fmaiassistent.memory.ClubIdentityLayout layout)throws IOException {
        validateClubIdentityLayout(layout);
        if(prefix.length!=16||layout.prefixBytes()!=16||layout.index()<8||layout.index()>12
                ||layout.uniqueId()<8||layout.uniqueId()>12||expectedIndex<0)
            throw new ClubIdentityChangedException("CLUB prefix/field width anchor failed");
        var b=java.nio.ByteBuffer.wrap(prefix).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        var known=com.github.fmaiassistent.memory.ClubIdentityLayout.current();
        long uid=Integer.toUnsignedLong(b.getInt(layout.uniqueId()));
        if(b.getInt(layout.index())!=expectedIndex||uid==0
                ||uid!=Integer.toUnsignedLong(b.getInt(known.uniqueId()))
                ||(b.getLong(0)!=base+layout.clubVtable()&&b.getLong(0)!=base+layout.leagueBodyVtable()))
            throw new ClubIdentityChangedException("CLUB slot/UID/type anchor failed: slot="+expectedIndex
                    +", nativeIndex="+b.getInt(layout.index())+", uid="+uid+", vtableRva="+Long.toHexString(b.getLong(0)-base));
        return uid;
    }

    public static void validateClubTeamIdentity(int expectedIndex,byte[] prefix,long base,
            com.github.fmaiassistent.memory.ClubIdentityLayout layout)throws IOException {
        validateClubIdentityLayout(layout);
        if(prefix.length!=16)throw new ClubIdentityChangedException("TEAM identity prefix width anchor failed");
        var b=java.nio.ByteBuffer.wrap(prefix).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        if(b.getInt(layout.index())!=expectedIndex||b.getInt(layout.uniqueId())==0
                ||(b.getLong(0)!=base+layout.teamVtable()&&b.getLong(0)!=base+layout.nationalTeamVtable()))
            throw new ClubIdentityChangedException("TEAM slot/UID/type anchor failed");
    }

    public static void validateClubIdentityLayout(com.github.fmaiassistent.memory.ClubIdentityLayout layout)throws IOException {
        if(!com.github.fmaiassistent.memory.ClubIdentityLayout.current().equals(layout))
            throw new ClubIdentityChangedException("CLUB/TEAM registry/native layout anchor failed");
    }

    /** Native contract anchors run before allocation and again before publication. */
    public static void validateContractPointer(long address, int bytes) throws IOException {
        if (address < 0x10000 || bytes < 0 || bytes > 65536
                || address > com.github.fmaiassistent.memory.ProcessMemoryReader.MAX_USER_ADDRESS - bytes) {
            throw new IOException("Contract snapshot failed pointer/range anchor");
        }
    }

    public static void validateContracts(List<com.github.fmaiassistent.domain.ContractSnapshot> contracts, long uid)
            throws IOException {
        for (var contract : contracts) {
            if ("BASIC".equals(contract.kind())
                    && (contract.coreHex().length() != com.github.fmaiassistent.memory.ContractRecordLayout.current().basic().size() * 2
                        || contract.agreedCode() != null || !contract.terms().isEmpty())) {
                throw new IOException("BASIC contract size/representation anchor failed");
            }
            boolean knownTeamOwner = "CLUB".equals(contract.teamOwnerKind())
                    || "NATIONAL_TEAM_CONTAINER".equals(contract.teamOwnerKind());
            if (contract.teamOwnerKind() != null && !knownTeamOwner
                    || contract.teamOwnerUid() != null && (!knownTeamOwner || contract.teamOwnerUid() <= 0
                        || contract.teamOwnerUid() > 0xFFFFFFFFL))
                throw new IOException("Contract TEAM owner kind/UID anchor failed");
            if (contract.ownerUid() != uid || uid <= 0 || contract.slot() < -1 || contract.slot() > 3
                    || contract.coreHex().length() < 32 || contract.coreHex().length() > 496
                    || contract.terms().size() > 16384
                    || contract.agreedCode() != null && (contract.agreedCode() < 0 || contract.agreedCode() > 255)
                    || contract.threshold() != null && (contract.threshold() < -128 || contract.threshold() > 127)
                    || contract.played() != null && (contract.played() < -128 || contract.played() > 127)) {
                throw new IOException("Contract snapshot failed owner/size/value anchor for " + uid);
            }
            // Captured valid conditions include20matches/36played. Never enforce played<=threshold.
            for (var term : contract.terms()) {
                validateCompetitionReferences(term);
                if (term.ordinal() < 0 || term.ordinal() >= 2048 || term.rawHex().length() > 64
                        || term.referenceUid() != null && (term.referenceUid() <= 0 || term.referenceUid() > 0xFFFFFFFFL
                            || !"AWARD".equals(term.referenceKind()))
                        || term.referenceKind() != null && !"AWARD".equals(term.referenceKind())
                        || term.codeRaw() != null && (term.codeRaw() < 0 || term.codeRaw() > 255)
                        || term.conditionRaw() != null && (term.conditionRaw() < -32768 || term.conditionRaw() > 32767)) {
                    throw new IOException("Contract term failed ordinal/width/value anchor for " + uid);
                }
            }
        }
    }

    public static void validateCompetitionReferences(com.github.fmaiassistent.domain.ContractSnapshot.Term term)throws IOException {
        var refs=term.competitionReferences();if(refs==null)return; // Compatible retained snapshots lack normalized references.
        if(!"FULL_80".equals(term.group()))throw new IOException("Competition references attached to wrong term group");
        var layout=com.github.fmaiassistent.memory.CompetitionBonusLayout.current();
        validateCompetitionBonus(term.rawHex(),layout);var row=layout.decode(term.rawHex());
        if((row.subjectCompetitionIndex()==-1)!=(refs.subjectUid()==null)
                ||(row.conditionCompetitionIndex()==-1)!=(refs.conditionUid()==null)
                ||refs.subjectUid()!=null&&(refs.subjectUid()<=0||refs.subjectUid()>0xffffffffL)
                ||refs.conditionUid()!=null&&(refs.conditionUid()<=0||refs.conditionUid()>0xffffffffL))
            throw new IOException("Competition reference UID/null anchor failed");
    }

    /** Fixed size plus cross-object identity prevent plausible values at shifted offsets being accepted. */
    public static void validateFutureTransferPerson(byte[] prefix,long uid,
            com.github.fmaiassistent.memory.FutureTransferLayout layout) throws IOException {
        validateFutureTransferLayout(layout);
        var b=java.nio.ByteBuffer.wrap(prefix).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        if(prefix.length!=0xB8||Integer.toUnsignedLong(b.getInt(12))!=uid||uid==0
                ||b.getLong(layout.personIndices())!=b.getLong(com.github.fmaiassistent.memory.FutureTransferLayout.current().personIndices()))
            throw new IOException("Future PERSON/index-vector anchor failed");
    }

    public static void validateFutureTransferOffer(byte[] raw,int index,
            com.github.fmaiassistent.memory.FutureTransferLayout layout) throws IOException {
        validateFutureTransferLayout(layout);
        var b=java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        var p=com.github.fmaiassistent.memory.FutureTransferLayout.current();
        if(raw.length!=p.offerPrefixSize()||b.getInt(layout.index())!=index||b.getInt(p.index())!=index
                ||raw[layout.status()]!=raw[p.status()]||b.getLong(layout.person())!=b.getLong(p.person())
                ||b.getLong(layout.buyerTeam())!=b.getLong(p.buyerTeam())
                ||b.getInt(layout.date())!=b.getInt(p.date())||raw[layout.dateKind()]!=raw[p.dateKind()])
            throw new IOException("Future offer index/width/profile anchor failed");
    }

    /** Root/type displacements are exact-image anchors, not values inferred from plausible records. */
    public static void validateContractLayout(com.github.fmaiassistent.memory.ContractRecordLayout layout) throws IOException {
        if(!layout.equals(com.github.fmaiassistent.memory.ContractRecordLayout.current()))
            throw new IOException("Contract native type/range layout anchor failed");
    }

    public static void validateFutureTransferLayout(com.github.fmaiassistent.memory.FutureTransferLayout layout) throws IOException {
        if(!layout.equals(com.github.fmaiassistent.memory.FutureTransferLayout.current()))
            throw new IOException("Future transfer native type/range layout anchor failed");
    }

    public static void validateFutureEmploymentLayout(com.github.fmaiassistent.memory.FutureEmploymentLayout layout) throws IOException {
        if(!layout.equals(com.github.fmaiassistent.memory.FutureEmploymentLayout.current()))
            throw new IOException("Future employment native type/range layout anchor failed");
    }

    public static void validateFutureEmployment(
            com.github.fmaiassistent.domain.FutureEmploymentSnapshot snapshot, long uid, int expectedOfferIndex)
            throws IOException {
        if (snapshot.offerIndex() != expectedOfferIndex || expectedOfferIndex < 0
                || snapshot.recordHex().length() != 0x48 * 2
                || snapshot.state() < 0 || snapshot.state() > 255
                || snapshot.wageRaw() < 0 || snapshot.wageRaw() > 100_000_000
                || !"FULL".equals(snapshot.contract().kind()) || snapshot.contract().slot() != -1
                || snapshot.contract().coreHex().length() != 0xC8 * 2
                || snapshot.endDate() != null && (snapshot.endDate().getYear() < 1900
                    || snapshot.endDate().getYear() > 3000)) {
            throw new IOException("Future employment size/offer/value anchor failed");
        }
        validateContracts(List.of(snapshot.contract()), uid);
        // Pin decoded scalars to the retained, exact-build profile. A shifted wage offset can
        // still produce a plausible wage, so a numeric range alone cannot detect that drift.
        var profile = com.github.fmaiassistent.memory.FutureEmploymentLayout.current();
        var rawRecord = java.nio.ByteBuffer.wrap(java.util.HexFormat.of().parseHex(snapshot.recordHex()))
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        var rawContract = java.nio.ByteBuffer.wrap(java.util.HexFormat.of().parseHex(snapshot.contract().coreHex()))
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        if (rawRecord.getInt(profile.recordOfferIndex()) != expectedOfferIndex
                || Byte.toUnsignedInt(rawRecord.get(profile.recordState())) != snapshot.state()
                || rawContract.getInt(profile.contractWage()) != snapshot.wageRaw()
                || ((Byte.toUnsignedInt(rawContract.get(profile.contractDateFlags())) & profile.gameDateFlagMask()) != 0)
                    != snapshot.endDateUsesGameDateFlag())
            throw new IOException("Future employment exact-build scalar anchor failed");
        if (!snapshot.endDateUsesGameDateFlag() && (snapshot.endDate() == null
                || (Short.toUnsignedInt(rawContract.getShort(profile.contractEnd())) & 511) != snapshot.endDate().getDayOfYear()
                || Short.toUnsignedInt(rawContract.getShort(profile.contractEnd() + 2)) != snapshot.endDate().getYear()))
            throw new IOException("Future employment exact-build end-date anchor failed");
    }

    /**
     * Validates decoded player rows. {@code gameDate} is the snapshot's game
     * date and may be blank, in which case date-relative checks are skipped.
     *
     * @throws IOException when any anchor fails
     */
    public static void validate(List<Map<String, Object>> rows, int peopleSlots, String gameDate)
            throws IOException {
        if (rows.isEmpty()) {
            if (peopleSlots > 0) {
                throw new IOException("Player snapshot failed anchor validation: "
                        + "no players decoded from " + peopleSlots + " people slots");
            }
            return;
        }
        LocalDate reference = parseDate(gameDate);
        for (Map<String, Object> row : rows) {
            String identity = "unique_id=" + row.get("unique_id");
            if (row.containsKey("unique_id")) {
                Long uniqueId = asLong(row.get("unique_id"));
                if (uniqueId == null || uniqueId <= 0) {
                    throw new IOException("Player snapshot failed anchor validation: "
                            + "invalid unique_id (" + identity + ")");
                }
            }
            checkRange(row, "ca", 1, 200, identity, "Player");
            checkRange(row, "pa", 1, 200, identity, "Player");
            checkRange(row, "age", 14, 60, identity, "Player");
            checkRange(row, "height_cm", 130, 230, identity, "Player");
            checkBirthDate(row, reference, identity, "Player", 70);
        }
    }

    /**
     * Validates decoded staff rows. Staff records carry no height and their
     * abilities use a zero-tolerant range because, unlike players, they pass
     * no classification gate. An empty staff table alongside decoded players
     * means the staff decode collapsed and aborts the load.
     *
     * @throws IOException when any anchor fails
     */
    public static void validateStaff(List<Map<String, Object>> rows, String gameDate, int playerCount)
            throws IOException {
        if (rows.isEmpty() && playerCount > 0) {
            throw new IOException("Staff snapshot failed anchor validation: "
                    + "no staff decoded while " + playerCount + " players decoded");
        }
        LocalDate reference = parseDate(gameDate);
        for (Map<String, Object> row : rows) {
            String identity = "unique_id=" + row.get("unique_id");
            if (row.containsKey("unique_id")) {
                Long uniqueId = asLong(row.get("unique_id"));
                if (uniqueId == null || uniqueId <= 0) {
                    throw new IOException("Staff snapshot failed anchor validation: "
                            + "invalid unique_id (" + identity + ")");
                }
            }
            checkRange(row, "ca", 0, 200, identity, "Staff");
            checkRange(row, "pa", 0, 200, identity, "Staff");
            // The native FM27 database includes active non-playing officials aged93/94.
            // Keep a human lifespan anchor without rejecting genuine senior staff.
            checkRange(row, "age", 16, 120, identity, "Staff");
            checkBirthDate(row, reference, identity, "Staff", 120);
        }
    }

    /**
     * Validates decoded club rows. Reputation duplicates the decode gate as
     * defense in depth. Club finances are not required to be non-negative:
     * FM uses negative transfer budgets for clubs that have already exceeded
     * their allocation, just as a negative balance can represent real debt.
     * Only extreme negative values retain a useful layout-drift anchor.
     * An empty club table alongside decoded players aborts the load.
     *
     * @throws IOException when any anchor fails
     */
    public static void validateClubs(List<Map<String, Object>> rows, int playerCount) throws IOException {
        if (rows.isEmpty() && playerCount > 0) {
            throw new IOException("Club snapshot failed anchor validation: "
                    + "no clubs decoded while " + playerCount + " players decoded");
        }
        for (Map<String, Object> row : rows) {
            String identity = "name=" + row.get("name");
            checkRange(row, "reputation", 1, 10000, identity, "Club");
            checkMinimum(row, "transferBudget", MIN_PLAUSIBLE_CLUB_BUDGET, identity);
            checkMinimum(row, "payrollBudget", MIN_PLAUSIBLE_CLUB_BUDGET, identity);
        }
    }

    /**
     * Validates decoded competition rows. Field-level gates already run at
     * decode (blank names and out-of-range reputations are dropped), so the
     * remaining anchor is the implausible all-record dropout: a save with
     * players always has competitions.
     *
     * @throws IOException when any anchor fails
     */
    public static void validateCompetitions(List<Map<String, Object>> rows, int playerCount)
            throws IOException {
        if (rows.isEmpty() && playerCount > 0) {
            throw new IOException("Competition snapshot failed anchor validation: "
                    + "no competitions decoded while " + playerCount + " players decoded");
        }
    }

    private static void checkRange(
            Map<String, Object> row,
            String field,
            long min,
            long max,
            String identity,
            String subject) throws IOException {
        Long value = asLong(row.get(field));
        if (value == null) {
            return;
        }
        if (value < min || value > max) {
            throw new IOException(subject + " snapshot failed anchor validation: "
                    + field + "=" + value + " out of range [" + min + ".." + max + "] (" + identity + ")");
        }
    }

    private static void checkMinimum(
            Map<String, Object> row, String field, long min, String identity) throws IOException {
        Long value = asLong(row.get(field));
        if (value == null) {
            return;
        }
        if (value < min) {
            throw new IOException("Club snapshot failed anchor validation: "
                    + field + "=" + value + " below minimum " + min + " (" + identity + ")");
        }
    }

    private static void checkBirthDate(
            Map<String, Object> row,
            LocalDate reference,
            String identity,
            String subject,
            int maxAgeYears)
            throws IOException {
        Object raw = row.get("date_of_birth");
        if (raw == null || String.valueOf(raw).isBlank()) {
            return;
        }
        LocalDate birthDate = parseDate(String.valueOf(raw));
        if (birthDate == null) {
            throw new IOException(subject + " snapshot failed anchor validation: "
                    + "malformed date_of_birth=" + raw + " (" + identity + ")");
        }
        if (reference == null) {
            return;
        }
        if (birthDate.isAfter(reference) || birthDate.isBefore(reference.minusYears(maxAgeYears))) {
            throw new IOException(subject + " snapshot failed anchor validation: "
                    + "implausible date_of_birth=" + raw + " (" + identity + ")");
        }
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException notNumeric) {
                return null;
            }
        }
        return null;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException notIsoDate) {
            return null;
        }
    }
}
