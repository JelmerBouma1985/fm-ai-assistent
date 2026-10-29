package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.domain.entity.ClubEntity;
import com.github.fmaiassistent.exporter.ClubExporter;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Span;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Explicitly enabled real-process/export and read-only actual-database/UI proof. */
@Tag("fm26-live")
class ClubFinanceApplicationLiveTest {
    private record Expected(long uid, long balance, Long transfer, Long payroll) { }

    @Test
    @EnabledIfSystemProperty(named="fm26.finance.live", matches="true")
    void supportedExporterPreservesIndependentCapturedMoney() throws Exception {
        int pid = Integer.getInteger("fm26.live.pid", -1);
        assertThat(pid).isEqualTo(6624);
        long started = System.nanoTime();
        var result = new ClubExporter().exportAllClubs(pid, 0x238BDD, null);
        var examples = new ArrayList<Map<String,Object>>();
        for (var sample : captured()) {
            var rows = result.rows().stream().filter(r -> ((Number)r.get("uniqueId")).longValue() == sample.uid()).toList();
            assertThat(rows).as("Native club UID " + sample.uid()).isNotEmpty();
            for (var row : rows) check(row.get("balance"), row.get("transferBudget"), row.get("payrollBudget"), sample);
            examples.add(Map.of("uid", sample.uid(), "views", rows.size(), "balance", sample.balance()));
        }
        report("fm26.finance.live.report", Map.of("pid", pid, "elapsedMillis", (System.nanoTime()-started)/1_000_000,
                "contextViews", result.rows().size(), "nativeClubs", result.nativeRecords().size(),
                "nullTransferBudgetViews", result.rows().stream().filter(r->r.get("transferBudget")==null).count(),
                "examples", examples, "capturedMoneyMatches", true));
    }

    @Test
    @EnabledIfSystemProperty(named="fm26.finance.database", matches="true")
    void everyStoredClubViewMatchesTheIndependentCurrentFinancialPopulation() throws Exception {
        byte[] data;
        try (var input = Files.newInputStream(Path.of(
                "docs/reverse-engineering/fm26/evidence/club_finance_variant_population_20261026.csv"))) {
            data = input.readNBytes(8 * 1024 * 1024 + 1);
        }
        assertThat(data.length).isLessThanOrEqualTo(8 * 1024 * 1024);
        var expected = new HashMap<Long,Expected>();
        var addresses = new HashMap<Long,Long>();
        var baseIds = new HashSet<Long>();
        for (String line : new String(data,java.nio.charset.StandardCharsets.US_ASCII).lines().skip(1).toList()) {
            String[] c = line.split(",",-1);
            assertThat(c).hasSize(12);
            long uid = Long.parseLong(c[1]), address = Long.parseUnsignedLong(c[2],16);
            var prefix = ByteBuffer.wrap(HexFormat.of().parseHex(c[6])).order(ByteOrder.LITTLE_ENDIAN);
            var finance = ByteBuffer.wrap(HexFormat.of().parseHex(c[7])).order(ByteOrder.LITTLE_ENDIAN);
            assertThat(prefix.capacity()).isEqualTo(16); assertThat(finance.capacity()).isEqualTo(64);
            assertThat(prefix.getLong()).isEqualTo(0x7FFAA0800000L+0x44BA518L);
            assertThat(prefix.getInt(8)).isEqualTo(Integer.parseInt(c[0]));
            assertThat(Integer.toUnsignedLong(prefix.getInt(12))).isEqualTo(uid);
            assertThat(finance.getLong()).isEqualTo(0x7FFAA0800000L+Long.parseUnsignedLong(c[4],16));
            assertThat(finance.getLong(8)).isEqualTo(address);
            long balance = finance.getInt(0x14);
            assertThat(balance).isEqualTo(Long.parseLong(c[5]));
            Long transfer = null, payroll = null;
            if (c[4].equals("456CE48")) {
                baseIds.add(uid);
                assertThat(c[8]).isEmpty(); assertThat(c[9]).isEmpty();
                assertThat(c[10]).isEmpty(); assertThat(c[11]).isEmpty();
            } else {
                assertThat(c[4]).isEqualTo("456D2E8");
                transfer = (long)ByteBuffer.wrap(HexFormat.of().parseHex(c[10])).order(ByteOrder.LITTLE_ENDIAN).getInt();
                payroll = (long)ByteBuffer.wrap(HexFormat.of().parseHex(c[11])).order(ByteOrder.LITTLE_ENDIAN).getInt();
                assertThat(transfer).isEqualTo(Long.parseLong(c[8]));
                assertThat(payroll).isEqualTo(Long.parseLong(c[9]));
            }
            assertThat(expected.put(uid,new Expected(uid,balance,transfer,payroll))).isNull();
            addresses.put(uid,address);
        }
        assertThat(expected).hasSize(26377); assertThat(baseIds).hasSize(25697);
        String url = System.getProperty("fm26.delivery.database.url");
        assertThat(url).startsWith("jdbc:h2:file:").contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        assertThat(jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='fm_pid'",String.class)).isEqualTo("6624");
        var covered = new HashSet<Long>(); var restored = new HashSet<Long>(); long nullViews = 0;
        var stored = jdbc.queryForList("SELECT unique_id,source_address,balance,transfer_budget,payroll_budget FROM clubs");
        for (var row : stored) {
            long uid = ((Number)row.get("UNIQUE_ID")).longValue();
            var sample = expected.get(uid); assertThat(sample).as("Native financial UID " + uid).isNotNull();
            assertThat(((Number)row.get("SOURCE_ADDRESS")).longValue()).isEqualTo(addresses.get(uid));
            check(row.get("BALANCE"),row.get("TRANSFER_BUDGET"),row.get("PAYROLL_BUDGET"),sample);
            covered.add(uid);
            if (baseIds.contains(uid)) { nullViews++; if (sample.balance()!=0) restored.add(uid); }
        }
        assertThat(stored).hasSize(31610); assertThat(covered).hasSize(23972);
        assertThat(restored).hasSize(5055); assertThat(nullViews).isEqualTo(30186);
        report("fm26.finance.population.report",Map.of("pid",6624,"nativeOrdinaryClubs",expected.size(),
                "baseFinancialObjects",baseIds.size(),"storedViewsChecked",stored.size(),"physicalClubsChecked",covered.size(),
                "nonzeroBaseClubsNowStored",restored.size(),"unavailableBudgetViews",nullViews,
                "allCurrentTypedRawMoneyMatchesDatabase",true,
                "limitation","Physical native values and stored views verified; only one independent game finance screenshot; canonical-only2405 ordinary clubs remain identity-only"));
    }

    @Test
    @EnabledIfSystemProperty(named="fm26.finance.database", matches="true")
    void actualAtomicSnapshotAndClubScreenShowPreciseMoneyAndUnknownBudgets() throws Exception {
        String url = System.getProperty("fm26.delivery.database.url");
        assertThat(url).startsWith("jdbc:h2:file:").contains("ACCESS_MODE_DATA=r", "IFEXISTS=TRUE");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));
        assertThat(jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='fm_pid'",String.class)).isEqualTo("6624");
        var examples = new ArrayList<Map<String,Object>>();
        for (var sample : captured()) {
            var rows = jdbc.queryForList("SELECT * FROM clubs WHERE unique_id=?", sample.uid());
            assertThat(rows).isNotEmpty();
            for (var row : rows) check(row.get("BALANCE"), row.get("TRANSFER_BUDGET"), row.get("PAYROLL_BUDGET"), sample);
            var values = new LinkedHashMap<String,Object>();
            for (String field : ClubExporter.FIELD_NAMES) {
                String column = field.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
                values.put(field, rows.getFirst().get(column));
            }
            ClubEntity club = ClubEntity.fromExportRow(values);
            var ui = new UI();
            ui.getInternals().setSession(mock(com.vaadin.flow.server.VaadinSession.class));
            UI.setCurrent(ui);
            try {
                var view = MainViewTest.view(new com.github.fmaiassistent.repository.ContractRepository(jdbc));
                var method = MainView.class.getDeclaredMethod("opponentClubView",ClubEntity.class,String.class);
                method.setAccessible(true);
                Component content = (Component)method.invoke(view,club,club.getName());
                assertThat(field(content,"Balance")).isEqualTo("£"+String.format(Locale.US,"%,d",sample.balance()));
                if (sample.payroll()==null) {
                    assertThat(field(content,"Payroll Budget (per week)")).isEmpty();
                    assertThat(field(content,"Transfer Budget")).isEmpty();
                } else {
                    assertThat(field(content,"Payroll Budget (per week)"))
                            .isEqualTo("£"+String.format(Locale.US,"%,d",sample.payroll()));
                }
                examples.add(Map.of("uid",sample.uid(),"balance",field(content,"Balance"),
                        "payroll",field(content,"Payroll Budget (per week)"),"views",rows.size()));
            } finally { UI.setCurrent(null); }
        }
        report("fm26.finance.database.report", Map.of("pid",6624,"database","actual snapshot, read-only",
                "examples",examples,"players",jdbc.queryForObject("SELECT COUNT(*) FROM players",Long.class),
                "staff",jdbc.queryForObject("SELECT COUNT(*) FROM staff",Long.class),
                "clubViews",jdbc.queryForObject("SELECT COUNT(*) FROM clubs",Long.class),
                "canonicalClubs",jdbc.queryForObject("SELECT COUNT(*) FROM club_records",Long.class),
                "nullBudgetViews",jdbc.queryForObject("SELECT COUNT(*) FROM clubs WHERE transfer_budget IS NULL AND payroll_budget IS NULL",Long.class),
                "limitation","Server components verified; browser pixels and canonical-only financial fields not delivered here"));
    }

    private static void check(Object balance,Object transfer,Object payroll,Expected sample) {
        assertThat(balance).isInstanceOf(Number.class);
        assertThat(((Number)balance).longValue()).isEqualTo(sample.balance());
        if (sample.transfer()==null) assertThat(transfer).isNull();
        else assertThat(((Number)transfer).longValue()).isEqualTo(sample.transfer());
        if (sample.payroll()==null) assertThat(payroll).isNull();
        else assertThat(((Number)payroll).longValue()).isEqualTo(sample.payroll());
    }

    private static List<Expected> captured() throws Exception {
        byte[] data;
        try(var input=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/club_finance_after_restart_live_20261026.json"))){
            data=input.readNBytes(65_537);
        }
        assertThat(data.length).isLessThanOrEqualTo(65_536);
        var live=JsonMapper.builder().build().readTree(data).get("returnValues").get(0);
        var expected=new ArrayList<Expected>();
        for(var s:live.get("samples")){
            var b=ByteBuffer.wrap(HexFormat.of().parseHex(s.get("financeRaw").asString())).order(ByteOrder.LITTLE_ENDIAN);
            boolean full=b.capacity()==0x8c0;
            expected.add(new Expected(s.get("uid").asLong(),b.getInt(0x14),
                    full?(long)b.getInt(0x7cc):null,full?(long)b.getInt(0x810):null));
        }
        return expected;
    }

    private static String field(Component root,String label) {
        return descendants(root).filter(c->c.getChildren().anyMatch(v->v instanceof Span s&&s.getText().equals(label)))
                .flatMap(c->c.getChildren().filter(Span.class::isInstance).map(Span.class::cast))
                .filter(s->s.hasClassName("detail-value")).map(Span::getText).findFirst().orElseThrow();
    }
    private static java.util.stream.Stream<Component> descendants(Component c){
        return c.getChildren().flatMap(v->java.util.stream.Stream.concat(java.util.stream.Stream.of(v),descendants(v)));
    }
    private static void report(String property,Object result) throws Exception {
        String output=System.getProperty(property);
        if(output!=null)Files.write(Path.of(output),JsonMapper.builder().build().writeValueAsBytes(result),StandardOpenOption.CREATE_NEW);
    }
}

