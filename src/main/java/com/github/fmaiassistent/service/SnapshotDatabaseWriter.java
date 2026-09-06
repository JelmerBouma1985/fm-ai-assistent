package com.github.fmaiassistent.service;

import com.github.fmaiassistent.domain.entity.ClubEntity;
import com.github.fmaiassistent.domain.entity.CompetitionEntity;
import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.domain.entity.StaffEntity;
import com.github.fmaiassistent.exporter.ClubExporter;
import com.github.fmaiassistent.exporter.CompetitionExporter;
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

    public void savePlayers(PlayerExporter.ExportResult result, Map<Long, Long> clubIds) {
        List<IdentifiedRow> rows = identified(result.rows());
        batchInsert("PLAYERS", PLAYER_COLUMNS, List.of("CLUB_ID", "PLAYING_CLUB_ID"), rows, Map.of(
                "CLUB_ID", row -> referencedId(row, "_club_address", clubIds),
                "PLAYING_CLUB_ID", row -> referencedId(row, "_playing_club_address", clubIds)));
    }

    public void saveStaff(StaffExporter.ExportResult result, Map<Long, Long> clubIds) {
        List<IdentifiedRow> rows = identified(result.rows());
        batchInsert("STAFF", STAFF_COLUMNS, List.of("CLUB_ID"), rows,
                Map.of("CLUB_ID", row -> referencedId(row, "_club_address", clubIds)));
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
            return column.staffText() ? "" : null;
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return column.staffText() ? "" : null;
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
