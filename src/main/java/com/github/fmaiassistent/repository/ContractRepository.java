package com.github.fmaiassistent.repository;

import com.github.fmaiassistent.domain.ContractSnapshot;
import com.github.fmaiassistent.domain.StoredContract;
import com.github.fmaiassistent.domain.FutureTransferSnapshot;
import com.github.fmaiassistent.domain.FutureEmploymentSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Reads a complete owner/term join in one statement, including the native UID to reject stale UI IDs. */
@Repository
public class ContractRepository {
    private static final int MAX_CONTRACTS = 5;
    private static final int MAX_TERMS = 16_384;
    private static final int MAX_JOIN_ROWS = MAX_CONTRACTS * (MAX_TERMS + 1);
    private final JdbcTemplate jdbc;

    public ContractRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<StoredContract> findPlayerContracts(Long playerId, Long nativeUid) {
        return find("player_id", playerId, nativeUid);
    }

    public List<StoredContract> findStaffContracts(Long staffId, Long nativeUid) {
        return find("staff_id", staffId, nativeUid);
    }

    private List<StoredContract> find(String ownerColumn, Long id, Long uid) {
        if (id == null || id <= 0 || uid == null || uid <= 0 || uid > 0xFFFF_FFFFL) return List.of();
        String sql = "SELECT c.*, n.name AS native_club_name,t.term_group,t.ordinal,t.raw_hex,t.value_raw,t.condition_raw,t.code_raw,"
                + "t.reference_kind,t.reference_uid,t.subject_competition_uid,t.condition_competition_uid,aw.unique_id AS award_uid,aw.record_index AS award_index,aw.prefix_hex AS award_prefix,"
                + "aw.name_hex AS award_name_hex,aw.short_name_hex AS award_short_hex,aw.name AS award_name,aw.short_name AS award_short,"
                + competitionNameColumns("sc")+","+competitionNameColumns("cc")+" "
                + "FROM player_contracts c LEFT JOIN contract_terms t ON t.contract_id=c.id "
                + "LEFT JOIN award_name_records aw ON t.reference_kind='AWARD' AND aw.unique_id=t.reference_uid AND aw.module_sha256=c.module_sha256 "
                + "LEFT JOIN competition_name_records sc ON sc.unique_id=t.subject_competition_uid AND sc.module_sha256=c.module_sha256 "
                + "LEFT JOIN competition_name_records cc ON cc.unique_id=t.condition_competition_uid AND cc.module_sha256=c.module_sha256 "
                + "LEFT JOIN club_records n ON c.team_owner_kind='CLUB' AND n.unique_id=c.team_owner_uid AND n.module_sha256=c.module_sha256 "
                + "WHERE c." + ownerColumn + "=? AND c.owner_uid=? ORDER BY CASE WHEN c.source='PERSON_LINK' THEN 0 ELSE 1 END,c.slot,c.offer_index,c.id,t.term_group,t.ordinal "
                + "FETCH FIRST " + (MAX_JOIN_ROWS + 1) + " ROWS ONLY";
        return jdbc.query(sql, rs -> {
            var found = new LinkedHashMap<Long,Accumulator>();
            int rows = 0;
            int activeCount=0,futureCount=0;
            while (rs.next()) {
                if (++rows > MAX_JOIN_ROWS) throw new IllegalStateException("Contract snapshot exceeds row limit");
                long contractId = rs.getLong("id");
                Accumulator entry = found.get(contractId);
                if (entry == null) {
                    boolean future="FUTURE_EMPLOYMENT".equals(rs.getString("source"));
                    if(future?++futureCount>256:++activeCount>MAX_CONTRACTS)
                        throw new IllegalStateException("Contract snapshot exceeds slot limit");
                    var h=header(rs);
                    entry = new Accumulator(contractId, rs.getString("module_sha256"), h,future(rs,h),rs.getString("native_club_name"));
                    found.put(contractId, entry);
                }
                String group = rs.getString("term_group");
                if (group != null) {
                    String awardName=awardName(rs);if(awardName!=null)entry.awardNames.put(rs.getLong("award_uid"),awardName);
                    if("FULL_80".equals(group)) {
                        try {
                            var decoded=com.github.fmaiassistent.memory.CompetitionBonusLayout.current().decode(rs.getString("raw_hex"));
                            String subject=competitionName(rs,"sc",decoded.subjectCompetitionIndex());
                            String condition=competitionName(rs,"cc",decoded.conditionCompetitionIndex());
                            if(subject!=null)entry.competitionNames.put(rs.getLong("sc_uid"),subject);
                            if(condition!=null)entry.competitionNames.put(rs.getLong("cc_uid"),condition);
                        }catch(IllegalArgumentException|IndexOutOfBoundsException invalid){ /* Preserve raw legacy terms without guessed names. */ }
                    }
                    if (entry.terms.size() >= MAX_TERMS) throw new IllegalStateException("Contract snapshot exceeds term limit");
                    entry.terms.add(new ContractSnapshot.Term(group, rs.getInt("ordinal"), rs.getString("raw_hex"),
                            nullableLong(rs,"value_raw"), nullableInt(rs,"condition_raw"), nullableInt(rs,"code_raw"),
                            rs.getString("reference_kind"), nullableLong(rs,"reference_uid"),competitionReferences(rs)));
                }
            }
            return found.values().stream().map(Accumulator::finish).toList();
        }, id, uid);
    }

    private static ContractSnapshot header(ResultSet r) throws SQLException {
        return new ContractSnapshot(r.getString("kind"),"FUTURE_EMPLOYMENT".equals(r.getString("source"))?-1:r.getInt("slot"),r.getLong("owner_uid"),nullableLong(r,"team_uid"),
                nullableInt(r,"agreed_code"),nullableInt(r,"base_fee"),nullableLong(r,"total_fee"),r.getString("fee_kind"),
                nullableInt(r,"match_threshold"),nullableInt(r,"matches_played"),nullableInt(r,"competition_index"),nullableLong(r,"competition_uid"),
                nullableInt(r,"condition_kind"),nullableInt(r,"internal_competition"),nullableInt(r,"context_1"),nullableInt(r,"context_2"),
                nullableLong(r,"flags_a0"),nullableLong(r,"flags_a8"),r.getString("core_hex"),r.getString("auxiliary_hex"),List.of(),
                r.getString("team_owner_kind"),nullableLong(r,"team_owner_uid"));
    }

    private static FutureTransferSnapshot future(ResultSet r,ContractSnapshot h)throws SQLException {
        if("PERSON_LINK".equals(r.getString("source")))return null;
        if(!"FUTURE_EMPLOYMENT".equals(r.getString("source")))throw new IllegalStateException("Unknown contract source");
        var date=r.getDate("future_transfer_date");var end=r.getDate("future_end_date");
        return new FutureTransferSnapshot(date==null?null:date.toLocalDate(),new FutureEmploymentSnapshot(
                r.getInt("offer_index"),r.getInt("future_record_state"),r.getString("future_record_hex"),
                r.getInt("future_wage_raw"),end==null?null:end.toLocalDate(),r.getBoolean("future_end_uses_game_date"),h));
    }

    private static Long nullableLong(ResultSet r,String column)throws SQLException {
        long value=r.getLong(column);return r.wasNull()?null:value;
    }
    private static ContractSnapshot.CompetitionReferences competitionReferences(ResultSet r)throws SQLException {
        Long subject=nullableLong(r,"subject_competition_uid"),condition=nullableLong(r,"condition_competition_uid");
        if(subject==null&&condition==null)return null;
        try {
            var row=com.github.fmaiassistent.memory.CompetitionBonusLayout.current().decode(r.getString("raw_hex"));
            // One term must not borrow another term's valid UID/name when its own native index disagrees.
            if(subject!=null&&r.getObject("sc_uid")!=null&&!competitionIdentityMatches(r,"sc",row.subjectCompetitionIndex())
                    ||condition!=null&&r.getObject("cc_uid")!=null&&!competitionIdentityMatches(r,"cc",row.conditionCompetitionIndex()))return null;
            return new ContractSnapshot.CompetitionReferences(subject,condition);
        }catch(IllegalArgumentException|IndexOutOfBoundsException invalid){return null;}
    }
    private static String competitionNameColumns(String alias) {
        return alias+".unique_id AS "+alias+"_uid,"+alias+".record_index AS "+alias+"_index,"+alias+".prefix_hex AS "+alias+"_prefix,"
                +alias+".name_hex AS "+alias+"_name_hex,"+alias+".short_name_hex AS "+alias+"_short_hex,"+alias+".name AS "+alias+"_name,"+alias+".short_name AS "+alias+"_short";
    }
    private static String competitionName(ResultSet r,String alias,int expectedIndex)throws SQLException {
        if(!competitionIdentityMatches(r,alias,expectedIndex))return null;
        try {
            var names=com.github.fmaiassistent.memory.CompetitionNameLayout.current().decode(r.getString(alias+"_name_hex"),r.getString(alias+"_short_hex"));
            if(!java.util.Objects.equals(names.name(),r.getString(alias+"_name"))||!java.util.Objects.equals(names.shortName(),r.getString(alias+"_short")))return null;
            return names.display().orElse(null);
        }catch(IllegalArgumentException|IndexOutOfBoundsException invalid){return null;}
    }
    private static boolean competitionIdentityMatches(ResultSet r,String alias,int expectedIndex)throws SQLException {
        if(r.getObject(alias+"_uid")==null||expectedIndex<0)return false;
        try {
            var l=com.github.fmaiassistent.memory.ClubFinancialCompetitionLayout.current();
            String hex=r.getString(alias+"_prefix");if(hex==null||hex.length()!=l.competitionPrefixBytes()*2)return false;
            var prefix=java.nio.ByteBuffer.wrap(java.util.HexFormat.of().parseHex(hex)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            return r.getInt(alias+"_index")==expectedIndex&&prefix.getInt(l.competitionIndexRel())==expectedIndex
                    &&Integer.toUnsignedLong(prefix.getInt(l.competitionUidRel()))==r.getLong(alias+"_uid");
        }catch(IllegalArgumentException|IndexOutOfBoundsException invalid){return false;}
    }
    private static String awardName(ResultSet r)throws SQLException {
        if(r.getObject("award_uid")==null)return null;
        try {
            if(!com.github.fmaiassistent.memory.AwardNameLayout.current().identityMatches(r.getLong("award_uid"),r.getInt("award_index"),r.getString("award_prefix")))return null;
            var names=com.github.fmaiassistent.memory.AwardNameLayout.current().decode(r.getString("award_name_hex"),r.getString("award_short_hex"));
            if(!java.util.Objects.equals(names.name(),r.getString("award_name"))||!java.util.Objects.equals(names.shortName(),r.getString("award_short")))return null;
            return names.display().orElse(null);
        }catch(IllegalArgumentException|IndexOutOfBoundsException invalid){return null;}
    }
    private static Integer nullableInt(ResultSet r,String column)throws SQLException {
        int value=r.getInt(column);return r.wasNull()?null:value;
    }
    private static final class Accumulator {
        final long id;
        final String profile;
        final ContractSnapshot h;
        final FutureTransferSnapshot future;
        final String clubName;
        final List<ContractSnapshot.Term> terms = new ArrayList<>();
        final java.util.Map<Long,String> awardNames=new LinkedHashMap<>();
        final java.util.Map<Long,String> competitionNames=new LinkedHashMap<>();
        Accumulator(long id,String profile,ContractSnapshot header,FutureTransferSnapshot future,String clubName){this.id=id;this.profile=profile;this.h=header;this.future=future;this.clubName=clubName;}
        StoredContract finish(){
            var complete=new ContractSnapshot(h.kind(),h.slot(),h.ownerUid(),h.teamUid(),h.agreedCode(),
                    h.baseFee(),h.totalFee(),h.feeKind(),h.threshold(),h.played(),h.competitionIndex(),h.competitionUid(),
                    h.conditionKind(),h.internalCompetition(),h.context1(),h.context2(),h.flagsA0(),h.flagsA8(),h.coreHex(),
                    h.auxiliaryHex(),terms,h.teamOwnerKind(),h.teamOwnerUid());
            var e=future==null?null:future.employment();
            return new StoredContract(id,profile,complete,future==null?null:new FutureTransferSnapshot(future.transferDate(),
                    new FutureEmploymentSnapshot(e.offerIndex(),e.state(),e.recordHex(),e.wageRaw(),e.endDate(),e.endDateUsesGameDateFlag(),complete)),clubName,awardNames,competitionNames);
        }
    }
}
