package com.github.fmaiassistent.memory;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Native fixtures only: these tests do not certify a production exporter or run FM. */
class Fm26CapturedClubUefaCoefficientEvidenceTest {
    private static final Path EVIDENCE = Path.of("docs/reverse-engineering/fm26/evidence");

    @Test
    @org.junit.jupiter.api.Tag("fm26-live")
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "fm26.uefa.database", matches = "true")
    void actualDatabaseIdentityCoverageDoesNotImplyCoefficientFieldsAreDelivered() throws Exception {
        String url = System.getProperty("fm26.delivery.database.url");
        assertThat(url).startsWith("jdbc:h2:file:").contains("ACCESS_MODE_DATA=r", "IFEXISTS=TRUE");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(url, "sa", ""));
        assertThat(jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='fm_pid'", String.class))
                .isEqualTo("6624");
        var captured = rows();
        var canonical = jdbc.queryForList("SELECT unique_id,source_address,kind FROM club_records");
        int canonicalCoefficients = 0;
        for (var row : canonical) {
            long uid = ((Number) row.get("UNIQUE_ID")).longValue();
            var raw = captured.get(uid);
            if (raw == null) {
                assertThat(row.get("KIND")).isEqualTo("LEAGUE_BODY_CLUB");
                continue;
            }
            assertThat(((Number) row.get("SOURCE_ADDRESS")).longValue())
                    .isEqualTo(Long.parseUnsignedLong(raw[2], 16));
            if (!raw[5].equals("0")) canonicalCoefficients++;
        }
        assertThat(canonical).hasSize(26839);
        assertThat(canonicalCoefficients).isEqualTo(1903);
        var viewUids = new java.util.HashSet<Long>();
        int coefficientViews = 0;
        var views = jdbc.queryForList("SELECT unique_id,source_address FROM clubs");
        for (var row : views) {
            long uid = ((Number) row.get("UNIQUE_ID")).longValue();
            var raw = captured.get(uid);
            assertThat(raw).isNotNull();
            assertThat(((Number) row.get("SOURCE_ADDRESS")).longValue())
                    .isEqualTo(Long.parseUnsignedLong(raw[2], 16));
            if (!raw[5].equals("0")) {
                coefficientViews++;
                viewUids.add(uid);
            }
        }
        var report = Map.of("pid", 6624, "canonicalCoefficientOwners", canonicalCoefficients,
                "contextualCoefficientViews", coefficientViews, "distinctContextualCoefficientOwners", viewUids.size(),
                "canonicalOnlyCoefficientOwners", canonicalCoefficients - viewUids.size(),
                "totalContextualViews", views.size(), "coefficientFieldsDelivered", false,
                "limitation", "Read-only actual database joins existing identities to separately captured native objects; no coefficient payload has been persisted yet.");
        String output = System.getProperty("fm26.uefa.database.report");
        assertThat(output).isNotBlank();
        Files.write(Path.of(output), JsonMapper.builder().build().writeValueAsBytes(report),
                java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    @Test
    void allocatedObjectsHaveIndependentIdentityAndNullPointersRemainAbsent() throws Exception {
        var rows = rows();
        assertThat(rows).hasSize(26377);
        int present = 0;
        var addresses = new java.util.HashSet<String>();
        for (var entry : rows.entrySet()) {
            var row = entry.getValue();
            var prefix = little(row[3]);
            assertThat(prefix.capacity()).isEqualTo(16);
            assertThat(prefix.getLong()).isEqualTo(0x7FFAA0800000L + 0x44BA518);
            assertThat(prefix.getInt(8)).isEqualTo(Integer.parseInt(row[0]));
            assertThat(Integer.toUnsignedLong(prefix.getInt(12))).isEqualTo(entry.getKey());
            if (row[5].equals("0")) {
                assertThat(row[6]).isEmpty();
                continue;
            }
            assertThat(addresses.add(row[5])).isTrue();
            var bytes = little(row[6]);
            assertThat(bytes.capacity()).isEqualTo(76);
            assertThat(Byte.toUnsignedInt(bytes.get(0))).isEqualTo(10);
            assertThat(Byte.toUnsignedInt(bytes.get(0x2C))).isZero();
            present++;
        }
        assertThat(present).isEqualTo(1903);
        var model = json("club_uefa_coefficient_model_verified_20261026.json");
        assertThat(model.get("nullCoefficientPointers").asInt()).isEqualTo(24474);
        assertThat(model.get("availability").get("presentUnavailable").asInt()).isZero();
        assertThat(model.get("codeBytesStaticRuntimeEqual").asInt()).isEqualTo(2577);
    }

    @Test
    void rankingsAndCurrentSeasonPointsUseDifferentNativeRepresentations() throws Exception {
        var rows = rows();
        var context = json("club_uefa_coefficient_context_live_20261026.json").get("returnValues").get(0);
        assertThat(context.get("guardsUnchanged").asBoolean()).isTrue();
        int rule = context.get("ruleVersion").asInt();
        assertThat(rule).isEqualTo(1639);
        int scale = rule > 0x653 ? 100 : 4;
        assertThat(scale).isEqualTo(context.get("currentPointsScale").asInt());
        var heerenveen = little(rows.get(1036L)[6]);
        assertThat(heerenveen.getInt(0x34) + 1).isEqualTo(16);
        assertThat(heerenveen.getInt(0x38) + 1).isEqualTo(28);
        assertThat(heerenveen.getInt(0x44) / (double) scale).isEqualTo(37);
        assertThat(heerenveen.getInt(0x44) / 1000.0).isNotEqualTo(37);
        var chelsea = little(rows.get(630L)[6]);
        assertThat(chelsea.getInt(0x44) / (double) scale).isEqualTo(25.25);
        assertThat(heerenveen.getInt(0x3C) / 1000.0).isEqualTo(116.5);
        assertThat(heerenveen.getInt(0x40) / 1000.0).isEqualTo(169.25);
        // The native builder dRwB uses history indices6..10;10 is outside count10.
        float builderValue = 0;
        for (int i = 6; i <= 10; i++) builderValue += nativeHistoryValue(heerenveen, i);
        builderValue += heerenveen.getInt(0x44) / (float) scale;
        assertThat(builderValue).isEqualTo(138);
    }

    @Test
    void storedEffectiveCoefficientsCannotBeReplacedByHistorySumsOrRoundedFloatCache() throws Exception {
        var rows = rows();
        int differingFive = 0, differingTen = 0, differingCache = 0;
        for (var r : rows.values()) {
            if (r[5].equals("0")) continue;
            var b = little(r[6]);
            int five = 0, ten = 0;
            for (int i = 0; i < 10; i++) {
                int v = b.getInt(4 + 4 * i);
                ten += v;
                if (i >= 5) five += v;
            }
            if (five != b.getInt(0x3C)) differingFive++;
            if (ten != b.getInt(0x40)) differingTen++;
            if (Math.abs(b.getFloat(0x30) - b.getInt(0x3C) / 1000.0) > 0.0001) differingCache++;
        }
        assertThat(differingFive).isEqualTo(858);
        assertThat(differingTen).isEqualTo(944);
        assertThat(differingCache).isEqualTo(727);
        var sample = little(rows.get(102L)[6]);
        assertThat(sample.getInt(0x3C)).isEqualTo(17369);
        assertThat(Math.round(sample.getFloat(0x30) * 1000)).isEqualTo(17370);
        var chelsea = little(rows.get(630L)[6]);
        assertThat(chelsea.getInt(0x40)).isEqualTo(277750);
    }

    @Test
    void nativeHistoryLeafUsesUnsignedCountAndCursorAndRejectsTheEleventhIndex() throws Exception {
        var b = little(rows().get(1036L)[6]);
        assertThat(nativeHistoryValue(b, 0)).isZero();
        assertThat(nativeHistoryValue(b, 9)).isEqualTo(27.25f);
        assertThat(nativeHistoryValue(b, 10)).isZero();
        // Synthetic mutations exercise branches absent from this save; not runtime observations.
        b.put(0x2C, (byte) 3);
        assertThat(nativeHistoryValue(b, 0)).isEqualTo(9.5f);
        assertThat(nativeHistoryValue(b, 9)).isEqualTo(14.75f);
        b.put(0, (byte) 2);
        assertThat(nativeHistoryValue(b, 2)).isZero();
        b.put(0, (byte) 0);
        assertThat(nativeHistoryValue(b, 0)).isZero();
    }

    private static float nativeHistoryValue(ByteBuffer b, int index) {
        // Independent replay of closed14-instruction2714160 CFG, not a production layout.
        if (index >= Byte.toUnsignedInt(b.get(0))) return 0;
        int slot = Byte.toUnsignedInt(b.get(0x2C)) + index;
        if (slot >= 10) slot -= 10;
        return (float) (b.getInt(4 + 4 * slot) / 1000.0);
    }

    private static Map<Long, String[]> rows() throws Exception {
        var result = new HashMap<Long, String[]>();
        String csv = new String(bounded("club_uefa_coefficients_population_live_20261026.csv", 16_777_216),
                java.nio.charset.StandardCharsets.US_ASCII);
        for (String line : csv.lines().skip(1).toList()) {
            var row = line.split(",", -1);
            assertThat(row).hasSize(7);
            assertThat(result.put(Long.parseLong(row[1]), row)).isNull();
        }
        return result;
    }

    private static JsonNode json(String name) throws Exception {
        return JsonMapper.builder().build().readTree(bounded(name, 1_048_576));
    }

    private static byte[] bounded(String name, int limit) throws Exception {
        try (var in = Files.newInputStream(EVIDENCE.resolve(name))) {
            byte[] bytes = in.readNBytes(limit + 1);
            assertThat(bytes.length).isLessThanOrEqualTo(limit);
            return bytes;
        }
    }

    private static ByteBuffer little(String hex) {
        return ByteBuffer.wrap(HexFormat.of().parseHex(hex)).order(ByteOrder.LITTLE_ENDIAN);
    }
}
