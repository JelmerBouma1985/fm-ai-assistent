package com.github.fmaiassistent.service;

import com.github.fmaiassistent.domain.entity.ClubEntity;
import com.github.fmaiassistent.domain.entity.CompetitionEntity;
import com.github.fmaiassistent.domain.entity.FixtureEntity;
import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.domain.entity.StaffEntity;
import com.github.fmaiassistent.exporter.ClubExporter;
import com.github.fmaiassistent.exporter.CompetitionExporter;
import com.github.fmaiassistent.exporter.FixtureExporter;
import com.github.fmaiassistent.exporter.PlayerExporter;
import com.github.fmaiassistent.exporter.StaffExporter;
import com.github.fmaiassistent.player.PlayerColumnNames;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a complete, already-decoded RAM snapshot without putting more than
 * 100,000 short-lived entities in Hibernate's persistence context.
 */
@Service
public class SnapshotDatabaseWriter {
    private static final int BATCH_SIZE = 500;
    private static final List<ExportColumn> COMPETITION_COLUMNS = columns(
            CompetitionEntity.class, CompetitionExporter.FIELD_NAMES, false);
    private static final List<ExportColumn> CLUB_COLUMNS = columns(
            ClubEntity.class, ClubExporter.FIELD_NAMES, false);
    private static final List<ExportColumn> PLAYER_COLUMNS = columns(
            PlayerEntity.class, PlayerExporter.FIELD_NAMES, true);
    private static final List<ExportColumn> STAFF_COLUMNS = columns(
            StaffEntity.class, StaffExporter.FIELD_NAMES, true);
    private static final List<ExportColumn> FIXTURE_COLUMNS = columns(
            FixtureEntity.class, FixtureExporter.FIELD_NAMES, false);

    private final JdbcTemplate jdbc;

    public SnapshotDatabaseWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Long, Long> saveCompetitions(CompetitionExporter.ExportResult result) {
        List<IdentifiedRow> rows = identified(result.rows());
        batchInsert("COMPETITIONS", COMPETITION_COLUMNS, List.of(), rows, Map.of());
        Map<Long, Long> idsByAddress = new HashMap<>();
        rows.forEach(row -> putAddress(idsByAddress, row, "sourceAddress"));
        return idsByAddress;
    }

    public Map<Long, Long> saveClubs(ClubExporter.ExportResult result, Map<Long, Long> competitionIds) {
        saveNativeClubs(result);
        List<IdentifiedRow> rows = identified(result.rows());
        batchInsert("CLUBS", CLUB_COLUMNS, List.of("COMPETITION_ID"), rows,
                Map.of("COMPETITION_ID", row -> referencedId(row, "_competition_address", competitionIds)));

        Map<Long, IdentifiedRow> bestByAddress = new LinkedHashMap<>();
        for (IdentifiedRow row : rows) {
            Object address = row.values().get("sourceAddress");
            if (address instanceof Number number) {
                bestByAddress.merge(number.longValue(), row, SnapshotDatabaseWriter::higherReputation);
            }
        }
        Map<Long, Long> idsByAddress = new HashMap<>();
        bestByAddress.forEach((address, row) -> idsByAddress.put(address, row.id()));
        return idsByAddress;
    }

    /** Shared by the atomic snapshot path and the older standalone club import. */
    public void saveNativeClubs(ClubExporter.ExportResult result) {
        var nativeRecords=result.nativeRecords();var byAddress=new HashMap<Long,Long>();var ids=new java.util.HashSet<Long>();
        var indices=new java.util.HashSet<Integer>();var values=new ArrayList<Object[]>();
        var requiredCompetitionUids=new java.util.HashSet<Long>();
        if(!nativeRecords.isEmpty()&&!com.github.fmaiassistent.exporter.ContractExporter.MODULE_SHA256.equals(result.identityProfile()))
            throw new IllegalArgumentException("Unsupported native CLUB profile");
        for(var record:nativeRecords) {
            if(record.uniqueId()<=0||record.uniqueId()>0xFFFFFFFFL||record.recordIndex()<0||record.prefixHex().length()!=32
                    ||!List.of("CLUB","LEAGUE_BODY_CLUB").contains(record.kind())||record.sourceAddress()<0x10000
                    ||record.sourceAddress()>com.github.fmaiassistent.memory.ProcessMemoryReader.MAX_USER_ADDRESS-16
                    ||record.name()!=null&&record.name().length()>1024
                    ||!ids.add(record.uniqueId())||!indices.add(record.recordIndex())
                    ||byAddress.put(record.sourceAddress(),record.uniqueId())!=null)
                throw new IllegalArgumentException("Invalid native CLUB identity");
            var raw=java.nio.ByteBuffer.wrap(java.util.HexFormat.of().parseHex(record.prefixHex())).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            var layout=com.github.fmaiassistent.memory.ClubIdentityLayout.current();
            if(raw.getInt(layout.index())!=record.recordIndex()||Integer.toUnsignedLong(raw.getInt(layout.uniqueId()))!=record.uniqueId())
                throw new IllegalArgumentException("Native CLUB prefix/identity mismatch");
            if(record.uefaCoefficientHex()!=null||record.uefaRuleVersion()!=null) {
                if(!"CLUB".equals(record.kind())||com.github.fmaiassistent.memory.ClubUefaCoefficientLayout.values(
                        record.uefaCoefficientHex(),record.uefaRuleVersion(),result.identityProfile()).isEmpty())
                    throw new IllegalArgumentException("Invalid native CLUB UEFA coefficient profile");
            }
            if(record.financialLoansHex()!=null||record.financialLoanClock()!=null) {
                if(!"CLUB".equals(record.kind())||com.github.fmaiassistent.memory.ClubFinancialLoanLayout.values(
                        record.financialLoansHex(),record.financialLoanClock(),result.identityProfile()).isEmpty())
                    throw new IllegalArgumentException("Invalid native CLUB financial loan profile");
            }
            if(record.financialLedgerHex()!=null||record.financialLedgerClock()!=null) {
                if(!"CLUB".equals(record.kind())||com.github.fmaiassistent.memory.ClubFinancialLedgerLayout.values(
                        record.financialLedgerHex(),record.financialLedgerClock(),result.identityProfile()).isEmpty())
                    throw new IllegalArgumentException("Invalid native CLUB financial ledger profile");
            }
            if(record.financialCompetitionsHex()!=null) {
                var profile=com.github.fmaiassistent.memory.ClubFinancialCompetitionLayout.values(record.financialCompetitionsHex(),
                        record.financialLedgerClock(),result.identityProfile(),record.financialLedgerHex());
                if(!"CLUB".equals(record.kind())||profile.isEmpty())throw new IllegalArgumentException("Invalid native CLUB financial competition profile");
                for(var period:profile.orElseThrow().periods()) {
                    period.tvRevenue().forEach(m->requiredCompetitionUids.add(m.competitionUid()));
                    period.prizeMoney().forEach(m->requiredCompetitionUids.add(m.competitionUid()));
                }
            }
            if(record.financialHistoryHex()!=null) {
                if(!"CLUB".equals(record.kind())||record.financialLedgerHex()==null
                        ||com.github.fmaiassistent.memory.ClubFinancialHistoryLayout.values(record.financialHistoryHex(),
                        record.financialLedgerClock(),result.identityProfile()).isEmpty())
                    throw new IllegalArgumentException("Invalid native CLUB financial history profile");
            }
            values.add(new Object[]{record.uniqueId(),record.recordIndex(),record.kind(),record.name(),record.sourceAddress(),record.prefixHex(),result.identityProfile(),
                    record.uefaCoefficientHex(),record.uefaRuleVersion(),record.financialLoansHex(),record.financialLoanClock(),
                    record.financialLedgerHex(),record.financialLedgerClock(),record.financialCompetitionsHex(),record.financialHistoryHex()});
        }
        for(var row:result.rows()) {
            Object rawUid=row.get("uniqueId");
            if(nativeRecords.isEmpty()&&rawUid==null)continue;
            if(!(rawUid instanceof Number uid)||!(row.get("sourceAddress") instanceof Number address)
                    ||!Long.valueOf(uid.longValue()).equals(byAddress.get(address.longValue())))
                throw new IllegalArgumentException("Club view/native identity mismatch");
        }
        var names=competitionNameValues(result,requiredCompetitionUids);
        if(!values.isEmpty())contractBatch("MERGE INTO club_records (unique_id,record_index,kind,name,source_address,prefix_hex,module_sha256,uefa_coefficient_hex,uefa_rule_version,financial_loans_hex,financial_loan_clock,financial_ledger_hex,financial_ledger_clock,financial_competitions_hex,financial_history_hex) KEY(unique_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",values);
        // A legacy/unsupported export has no name capture: never carry labels from an older snapshot.
        jdbc.update("DELETE FROM competition_name_records");
        if(!names.isEmpty())contractBatch("INSERT INTO competition_name_records (unique_id,record_index,source_address,prefix_hex,module_sha256,name_hex,short_name_hex,name,short_name) VALUES (?,?,?,?,?,?,?,?,?)",names);
    }

    private List<Object[]> competitionNameValues(ClubExporter.ExportResult result,java.util.Set<Long> required) {
        return competitionNameValues(result.competitionNames(),result.identityProfile(),required);
    }

    private List<Object[]> competitionNameValues(List<com.github.fmaiassistent.domain.NativeCompetitionNameSnapshot> captured,
            String profile,java.util.Set<Long> required) {
        var values=new ArrayList<Object[]>();if(captured==null)return values;
        if(!com.github.fmaiassistent.exporter.ContractExporter.MODULE_SHA256.equals(profile))
            throw new IllegalArgumentException("Unsupported native COMP names profile");
        var layout=com.github.fmaiassistent.memory.CompetitionNameLayout.current();var identity=com.github.fmaiassistent.memory.ClubFinancialCompetitionLayout.current();
        var ids=new java.util.HashSet<Long>();var indices=new java.util.HashSet<Integer>();var addresses=new java.util.HashSet<Long>();
        for(var record:captured) {
            if(record.uniqueId()<=0||record.uniqueId()>0xFFFFFFFFL||record.recordIndex()<0||record.prefixHex()==null||record.prefixHex().length()!=identity.competitionPrefixBytes()*2
                    ||record.sourceAddress()<0x10000||record.sourceAddress()>com.github.fmaiassistent.memory.ProcessMemoryReader.MAX_USER_ADDRESS-identity.competitionPrefixBytes()
                    ||!ids.add(record.uniqueId())||!indices.add(record.recordIndex())||!addresses.add(record.sourceAddress()))throw new IllegalArgumentException("Invalid native COMP name identity");
            var prefix=java.nio.ByteBuffer.wrap(java.util.HexFormat.of().parseHex(record.prefixHex())).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if(prefix.getInt(identity.competitionIndexRel())!=record.recordIndex()||Integer.toUnsignedLong(prefix.getInt(identity.competitionUidRel()))!=record.uniqueId())
                throw new IllegalArgumentException("Native COMP name prefix/identity mismatch");
            var decoded=layout.decode(record.nameHex(),record.shortNameHex());
            if(!java.util.Objects.equals(decoded.name(),record.name())||!java.util.Objects.equals(decoded.shortName(),record.shortName()))
                throw new IllegalArgumentException("Native COMP name bytes/text mismatch");
            values.add(new Object[]{record.uniqueId(),record.recordIndex(),record.sourceAddress(),record.prefixHex(),profile,record.nameHex(),record.shortNameHex(),record.name(),record.shortName()});
        }
        if(!ids.equals(required))throw new IllegalArgumentException("Incomplete or unrelated native COMP name references");
        return values;
    }

    public void savePlayers(PlayerExporter.ExportResult result, Map<Long, Long> clubIds) {
        List<IdentifiedRow> rows = identified(result.rows());
        batchInsert("PLAYERS", PLAYER_COLUMNS, List.of("CLUB_ID", "PLAYING_CLUB_ID"), rows, Map.of(
                "CLUB_ID", row -> referencedId(row, "_club_address", clubIds),
                "PLAYING_CLUB_ID", row -> referencedId(row, "_playing_club_address", clubIds)));
        saveContracts(rows, false);
    }

    private void saveContracts(List<IdentifiedRow> owners, boolean staffOwner) {
        Long contractId = null;
        List<Object[]> contracts = new ArrayList<>();
        List<Object[]> terms = new ArrayList<>();
        var awardNames=new LinkedHashMap<Long,com.github.fmaiassistent.domain.NativeAwardNameSnapshot>();
        var competitionNames=new LinkedHashMap<Long,Object[]>();
        for (var person : owners) {
            var entries=new ArrayList<ContractEntry>();
            Object raw = person.values().get("_contracts");
            if (raw instanceof List<?> snapshots) for (Object item : snapshots) {
                if (!(item instanceof com.github.fmaiassistent.domain.ContractSnapshot c))
                    throw new IllegalArgumentException("Invalid typed contract snapshot");
                entries.add(new ContractEntry(c,null));
            }
            Object futureRaw=person.values().get("_future_contracts");
            if(futureRaw instanceof List<?> futures)for(Object item:futures) {
                if(!(item instanceof com.github.fmaiassistent.domain.FutureTransferSnapshot future)
                        ||future.employment().offerIndex()<0||!"FULL".equals(future.employment().contract().kind()))
                    throw new IllegalArgumentException("Invalid typed future contract snapshot");
                entries.add(new ContractEntry(future.employment().contract(),future));
            }
            for(var entry:entries) {
                var c=entry.contract();var future=entry.future();var employment=future==null?null:future.employment();
                if(employment!=null) {
                    try {com.github.fmaiassistent.exporter.PlayerSnapshotValidator.validateFutureEmployment(employment,c.ownerUid(),employment.offerIndex());}
                    catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid future contract representation",invalid);}
                }
                if (!(person.values().get("unique_id") instanceof Number uid) || uid.longValue() != c.ownerUid())
                    throw new IllegalArgumentException(staffOwner ? "Contract/staff identity mismatch" : "Contract/player identity mismatch");
                if (contractId == null) contractId = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM player_contracts", Long.class);
                long id = ++contractId;
                contracts.add(new Object[]{id, staffOwner ? null : person.id(), staffOwner ? person.id() : null,
                        c.kind(), future==null?c.slot():null, c.ownerUid(), c.teamUid(),
                        c.agreedCode(), c.baseFee(), c.totalFee(), c.feeKind(), c.threshold(), c.played(),
                        c.competitionIndex(), c.competitionUid(), c.conditionKind(), c.internalCompetition(),
                        c.context1(), c.context2(), c.flagsA0(), c.flagsA8(), c.coreHex(), c.auxiliaryHex(),
                        com.github.fmaiassistent.exporter.ContractExporter.MODULE_SHA256, c.teamOwnerKind(), c.teamOwnerUid(),
                        future==null?"PERSON_LINK":"FUTURE_EMPLOYMENT",employment==null?null:employment.offerIndex(),
                        future==null?null:future.transferDate(),employment==null?null:employment.endDate(),
                        employment==null?null:employment.wageRaw(),employment==null?null:employment.recordHex(),
                        employment==null?null:employment.state(),employment==null?null:employment.endDateUsesGameDateFlag()});
                for (var term : c.terms()) {
                    try{com.github.fmaiassistent.exporter.PlayerSnapshotValidator.validateCompetitionReferences(term);}
                    catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid competition term references",invalid);}
                    var refs=term.competitionReferences();
                    terms.add(new Object[]{id, term.group(), term.ordinal(), term.rawHex(),
                        term.valueRaw(), term.conditionRaw(), term.codeRaw(), term.referenceKind(), term.referenceUid(),
                        refs==null?null:refs.subjectUid(),refs==null?null:refs.conditionUid()});
                }
            }
            Object namesRaw=person.values().get("_award_names");
            if(namesRaw!=null){
                if(!(namesRaw instanceof List<?> captured)||!com.github.fmaiassistent.exporter.ContractExporter.MODULE_SHA256.equals(person.values().get("_contract_profile")))
                    throw new IllegalArgumentException("Unsupported AWARD name source");
                var required=new java.util.HashSet<Long>();for(var e:entries)for(var t:e.contract().terms())
                    if("AWARD".equals(t.referenceKind())&&t.referenceUid()!=null)required.add(t.referenceUid());
                var found=new java.util.HashSet<Long>();for(var item:captured){
                    if(!(item instanceof com.github.fmaiassistent.domain.NativeAwardNameSnapshot n))throw new IllegalArgumentException("Invalid AWARD name snapshot");
                    if(n.uniqueId()<=0||n.uniqueId()>0xffffffffL||n.recordIndex()<0||n.recordIndex()>=100000||n.prefixHex()==null||n.prefixHex().length()!=32||!found.add(n.uniqueId()))
                        throw new IllegalArgumentException("Invalid AWARD name identity");
                    if(!com.github.fmaiassistent.memory.AwardNameLayout.current().identityMatches(n.uniqueId(),n.recordIndex(),n.prefixHex()))throw new IllegalArgumentException("AWARD prefix identity mismatch");
                    var decoded=com.github.fmaiassistent.memory.AwardNameLayout.current().decode(n.nameHex(),n.shortNameHex());
                    if(!java.util.Objects.equals(decoded.name(),n.name())||!java.util.Objects.equals(decoded.shortName(),n.shortName()))throw new IllegalArgumentException("AWARD raw name mismatch");
                    var previous=awardNames.putIfAbsent(n.uniqueId(),n);if(previous!=null&&!previous.equals(n))throw new IllegalArgumentException("Conflicting AWARD name snapshots");
                }
                if(!found.equals(required))throw new IllegalArgumentException("Incomplete or unrelated AWARD name references");
            }
            Object competitionNamesRaw=person.values().get("_contract_competition_names");
            if(competitionNamesRaw!=null) {
                if(!(competitionNamesRaw instanceof List<?> captured))throw new IllegalArgumentException("Invalid contract COMP names source");
                var typed=new ArrayList<com.github.fmaiassistent.domain.NativeCompetitionNameSnapshot>();
                for(var item:captured) {
                    if(!(item instanceof com.github.fmaiassistent.domain.NativeCompetitionNameSnapshot name))throw new IllegalArgumentException("Invalid contract COMP name snapshot");
                    typed.add(name);
                }
                var required=new java.util.HashSet<Long>();
                for(var e:entries)for(var t:e.contract().terms())if(t.competitionReferences()!=null) {
                    var refs=t.competitionReferences();if(refs.subjectUid()!=null)required.add(refs.subjectUid());
                    if(refs.conditionUid()!=null)required.add(refs.conditionUid());
                }
                for(var name:competitionNameValues(typed,String.valueOf(person.values().get("_contract_profile")),required)) {
                    var old=competitionNames.putIfAbsent((Long)name[0],name);
                    if(old!=null&&!java.util.Arrays.deepEquals(old,name))throw new IllegalArgumentException("Conflicting contract COMP names");
                }
            }
        }
        for(var name:competitionNames.values()) {
            var existing=jdbc.query("SELECT unique_id,record_index,source_address,prefix_hex,module_sha256,name_hex,short_name_hex,name,short_name FROM competition_name_records WHERE unique_id=?",
                    (r,n)->new Object[]{r.getLong(1),r.getInt(2),r.getLong(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9)},name[0]);
            if(!existing.isEmpty()&&!java.util.Arrays.deepEquals(existing.getFirst(),name))throw new IllegalArgumentException("Contract/financial COMP name conflict");
        }
        if(!competitionNames.isEmpty())contractBatch("MERGE INTO competition_name_records (unique_id,record_index,source_address,prefix_hex,module_sha256,name_hex,short_name_hex,name,short_name) KEY(unique_id) VALUES (?,?,?,?,?,?,?,?,?)",new ArrayList<>(competitionNames.values()));
        if(!awardNames.isEmpty())contractBatch("MERGE INTO award_name_records (unique_id,record_index,prefix_hex,module_sha256,name_hex,short_name_hex,name,short_name) KEY(unique_id) VALUES (?,?,?,?,?,?,?,?)",
                awardNames.values().stream().map(n->new Object[]{n.uniqueId(),n.recordIndex(),n.prefixHex(),com.github.fmaiassistent.exporter.ContractExporter.MODULE_SHA256,n.nameHex(),n.shortNameHex(),n.name(),n.shortName()}).toList());
        if (!contracts.isEmpty()) contractBatch("INSERT INTO player_contracts "
                + "(id,player_id,staff_id,kind,slot,owner_uid,team_uid,agreed_code,base_fee,total_fee,fee_kind,match_threshold,matches_played,"
                + "competition_index,competition_uid,condition_kind,internal_competition,context_1,context_2,flags_a0,flags_a8,core_hex,auxiliary_hex,module_sha256,team_owner_kind,team_owner_uid,"
                + "source,offer_index,future_transfer_date,future_end_date,future_wage_raw,future_record_hex,future_record_state,future_end_uses_game_date) "
                + "VALUES ("+String.join(",",java.util.Collections.nCopies(34,"?"))+")", contracts);
        if (!terms.isEmpty()) contractBatch("INSERT INTO contract_terms "
                + "(contract_id,term_group,ordinal,raw_hex,value_raw,condition_raw,code_raw,reference_kind,reference_uid,subject_competition_uid,condition_competition_uid) VALUES (?,?,?,?,?,?,?,?,?,?,?)", terms);
    }

    private record ContractEntry(com.github.fmaiassistent.domain.ContractSnapshot contract,
                                  com.github.fmaiassistent.domain.FutureTransferSnapshot future) { }

    private void contractBatch(String sql, List<Object[]> rows) {
        jdbc.batchUpdate(sql, rows, BATCH_SIZE, (statement, values) -> {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
        });
    }

    public void saveStaff(StaffExporter.ExportResult result, Map<Long, Long> clubIds) {
        List<IdentifiedRow> rows = identified(result.rows());
        batchInsert("STAFF", STAFF_COLUMNS, List.of("CLUB_ID"), rows,
                Map.of("CLUB_ID", row -> referencedId(row, "_club_address", clubIds)));
        saveContracts(rows, true);
    }

    public void saveFixtures(FixtureExporter.ExportResult result, Map<Long, Long> clubIds) {
        List<IdentifiedRow> rows = identified(result.rows());
        batchInsert("FIXTURES", FIXTURE_COLUMNS,
                List.of("HOME_CLUB_ID", "AWAY_CLUB_ID", "OPPONENT_CLUB_ID"), rows, Map.of(
                        "HOME_CLUB_ID", row -> referencedId(row, "_home_club_address", clubIds),
                        "AWAY_CLUB_ID", row -> referencedId(row, "_away_club_address", clubIds),
                        "OPPONENT_CLUB_ID", row -> referencedId(row, "_opponent_club_address", clubIds)));
    }

    private void batchInsert(
            String table,
            List<ExportColumn> exportColumns,
            List<String> referenceColumns,
            List<IdentifiedRow> rows,
            Map<String, RowValue> referenceValues) {
        if (rows.isEmpty()) {
            return;
        }
        List<String> databaseColumns = new ArrayList<>();
        databaseColumns.add("ID");
        exportColumns.stream().map(ExportColumn::databaseName).forEach(databaseColumns::add);
        databaseColumns.addAll(referenceColumns);
        String sql = "INSERT INTO " + quoted(table) + " ("
                + databaseColumns.stream().map(SnapshotDatabaseWriter::quoted).reduce((a, b) -> a + "," + b).orElseThrow()
                + ") VALUES (" + String.join(",", java.util.Collections.nCopies(databaseColumns.size(), "?")) + ")";

        jdbc.batchUpdate(sql, rows, BATCH_SIZE, (statement, row) -> {
            int parameter = 1;
            statement.setLong(parameter++, row.id());
            for (ExportColumn column : exportColumns) {
                Object value = normalizedValue(row, column);
                statement.setObject(parameter++, value);
            }
            for (String referenceColumn : referenceColumns) {
                setNullableLong(statement, parameter++, referenceValues.get(referenceColumn).get(row));
            }
        });
    }

    private static Object normalizedValue(IdentifiedRow row, ExportColumn column) {
        Object value = row.values().get(column.exportName());
        if (value == null) {
            return missingValue(column);
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return missingValue(column);
        }
        if (column.type() == String.class) {
            return text;
        }
        if (!(value instanceof Number number)) {
            if (column.type() == Boolean.class) {
                return Boolean.valueOf(text);
            }
            return null;
        }
        if (column.type() == Integer.class) {
            return number.intValue();
        }
        if (column.type() == Long.class) {
            return number.longValue();
        }
        if (column.type() == Boolean.class) {
            return number.intValue() != 0;
        }
        throw new IllegalArgumentException("Unsupported snapshot field type: " + column.type().getName());
    }

    private static Object missingValue(ExportColumn column) {
        if (column.type() == Boolean.class && "on_duty".equals(column.exportName())) {
            return Boolean.FALSE;
        }
        return column.staffText() && !column.exportName().startsWith("non_playing_profile_") ? "" : null;
    }

    private static List<ExportColumn> columns(Class<?> entityType, List<String> exportNames, boolean snakeCaseFields) {
        List<ExportColumn> columns = new ArrayList<>(exportNames.size());
        for (String exportName : exportNames) {
            String fieldName = snakeCaseFields ? PlayerColumnNames.toEntityFieldName(exportName) : exportName;
            try {
                Field field = entityType.getDeclaredField(fieldName);
                columns.add(new ExportColumn(
                        exportName,
                        PlayerColumnNames.toColumnName(exportName).toUpperCase(),
                        field.getType(),
                        entityType == PlayerEntity.class,
                        entityType == StaffEntity.class && field.getType() == String.class));
            } catch (NoSuchFieldException exception) {
                throw new IllegalStateException("Unmapped " + entityType.getSimpleName() + " export field: " + exportName,
                        exception);
            }
        }
        return List.copyOf(columns);
    }

    private static List<IdentifiedRow> identified(List<Map<String, Object>> rows) {
        List<IdentifiedRow> identified = new ArrayList<>(rows.size());
        long id = 1;
        for (Map<String, Object> row : rows) {
            identified.add(new IdentifiedRow(id++, row));
        }
        return identified;
    }

    private static void putAddress(Map<Long, Long> idsByAddress, IdentifiedRow row, String field) {
        Object address = row.values().get(field);
        if (address instanceof Number number) {
            idsByAddress.put(number.longValue(), row.id());
        }
    }

    private static Long referencedId(IdentifiedRow row, String field, Map<Long, Long> idsByAddress) {
        Object address = row.values().get(field);
        return address instanceof Number number ? idsByAddress.get(number.longValue()) : null;
    }

    private static IdentifiedRow higherReputation(IdentifiedRow left, IdentifiedRow right) {
        int leftReputation = number(left.values().get("reputation"));
        int rightReputation = number(right.values().get("reputation"));
        return rightReputation > leftReputation ? right : left;
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static void setNullableLong(PreparedStatement statement, int parameter, Object value) throws SQLException {
        if (value instanceof Number number) {
            statement.setLong(parameter, number.longValue());
        } else {
            statement.setObject(parameter, null);
        }
    }

    private static String quoted(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @FunctionalInterface
    private interface RowValue {
        Object get(IdentifiedRow row);
    }

    private record IdentifiedRow(long id, Map<String, Object> values) {
    }

    private record ExportColumn(
            String exportName,
            String databaseName,
            Class<?> type,
            boolean playerColumn,
            boolean staffText) {
    }
}
