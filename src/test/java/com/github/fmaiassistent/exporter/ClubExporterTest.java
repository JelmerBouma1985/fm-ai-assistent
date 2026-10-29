package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.ClubRecordLayout;
import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClubExporterTest {
    private static final long CLUB = 0x1000_0000L;
    private static final long FACILITIES = 0x2000_0000L;
    private static final long EXTRA = 0x3000_0000L;

    @Test
    void preservesExactHeerenveenMoneyFromCapturedRamAndFinanceScreenshot() throws Exception {
        var captured = capturedFinance(1036);
        var finance = new ClubExporter().readFinance(captured.memory(), captured.club(), captured.base());
        org.assertj.core.api.SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(finance.balance()).as("Screenshot overall balance, GBP").isEqualTo(213_254_902L);
            soft.assertThat(finance.transferBudget()).as("Raw transfer budget; screenshot abbreviates 75.89M")
                    .isEqualTo(75_892_576L);
            soft.assertThat(finance.payrollBudget()).as("Screenshot weekly wage budget, GBP").isEqualTo(2_173_526L);
        });
    }

    @Test
    void baseFinancialObjectRetainsBalanceWithoutReadingExtendedBudgets() throws Exception {
        var captured = capturedFinance(58); // Exactly64 mapped bytes: extended reads fail.
        var finance = new ClubExporter().readFinance(captured.memory(), captured.club(), captured.base());
        assertThat(finance.balance()).isEqualTo(86_223L);
        assertThat((Object) finance.transferBudget()).isNull();
        assertThat((Object) finance.payrollBudget()).isNull();
        assertThat(finance.currentWagesIncludingBonuses()).isNull();
    }

    @Test
    void capturedNegativeBalanceIsSignedAndNotRounded() throws Exception {
        var captured = capturedFinance(630);
        var finance = new ClubExporter().readFinance(captured.memory(), captured.club(), captured.base());
        assertThat(finance.balance()).isEqualTo(-104_591_370L);
    }

    @Test
    void rejectsEveryShiftedFinancialTypeExtentAndOwnerAnchorAgainstTheSameCapture() throws Exception {
        var captured = capturedFinance(1036);
        var known = com.github.fmaiassistent.memory.ClubFinanceLayout.current();
        var components = known.getClass().getRecordComponents();
        var constructor = known.getClass().getDeclaredConstructor(java.util.Arrays.stream(components)
                .map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new));
        for (int i = 0; i < components.length; i++) {
            Object[] values = new Object[components.length];
            for (int j = 0; j < values.length; j++) values[j] = components[j].getAccessor().invoke(known);
            if (values[i] instanceof Long value) values[i] = value + 8L;
            else values[i] = ((Integer) values[i]) + 1;
            var shifted = constructor.newInstance(values);
            assertThatThrownBy(() -> new ClubExporter().readFinance(captured.memory(), captured.club(),
                    captured.base(), shifted)).as(components[i].getName()).isInstanceOf(IOException.class)
                    .hasMessageContaining("native displacement anchor");
        }
    }

    @Test
    void rejectsShiftedExistingMoneyOffsetsAndSwappedFinancialOwner() throws Exception {
        var captured = capturedFinance(1036);
        var known = ClubRecordLayout.current();
        var components = known.getClass().getRecordComponents();
        var constructor = known.getClass().getDeclaredConstructor(java.util.Arrays.stream(components)
                .map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new));
        var names = java.util.Set.of("clubFinanceBlockRel", "clubBalanceRel", "clubTransferBudgetRel", "clubPayrollBudgetRel");
        for (int i = 0; i < components.length; i++) {
            if (!names.contains(components[i].getName())) continue;
            Object[] values = new Object[components.length];
            for (int j = 0; j < values.length; j++) values[j] = components[j].getAccessor().invoke(known);
            values[i] = ((Long) values[i]) + 4L;
            var shifted = constructor.newInstance(values);
            assertThatThrownBy(() -> new ClubExporter(shifted).readFinance(captured.memory(), captured.club(), captured.base()))
                    .as(components[i].getName()).isInstanceOf(IOException.class).hasMessageContaining("native displacement anchor");
        }
        long financial = captured.memory().readU64(captured.club() + known.clubFinanceBlockRel());
        captured.memory().putLong(financial + 8, captured.club() + 16);
        assertThatThrownBy(() -> new ClubExporter().readFinance(captured.memory(), captured.club(), captured.base()))
                .isInstanceOf(IOException.class).hasMessageContaining("native type/owner anchor");
    }

    private record CapturedFinance(FakeMemory memory, long club, long base) { }

    private static CapturedFinance capturedFinance(long uid) throws Exception {
        byte[] bytes;
        try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of(
                "docs/reverse-engineering/fm26/evidence/club_finance_after_restart_live_20261026.json"))) {
            bytes = input.readNBytes(65_537);
        }
        assertThat(bytes.length).isLessThanOrEqualTo(65_536);
        var receipt = tools.jackson.databind.json.JsonMapper.builder().build().readTree(bytes);
        assertThat(receipt.get("ok").asBoolean()).isTrue();
        var live = receipt.get("returnValues").get(0);
        assertThat(live.get("guardsUnchanged").asBoolean()).isTrue();
        long base = Long.parseUnsignedLong(live.get("base").asString(), 16);
        for (var sample : live.get("samples")) {
            if (sample.get("uid").asLong() != uid) continue;
            long club = Long.parseUnsignedLong(sample.get("club").asString(), 16);
            long finance = Long.parseUnsignedLong(sample.get("finance").asString(), 16);
            var memory = new FakeMemory();
            memory.putLong(club + 0x150, finance);
            memory.put(finance, java.util.HexFormat.of().parseHex(sample.get("financeRaw").asString()));
            return new CapturedFinance(memory, club, base);
        }
        throw new AssertionError("Missing independent native UID " + uid);
    }

    @Test
    void readsFm26FacilityRatingsFromTheClubSubstructures() throws Exception {
        FakeMemory memory = new FakeMemory();
        memory.putLong(CLUB + 0x100, FACILITIES);
        memory.putU8(FACILITIES + 0x118, 18);
        memory.putU8(FACILITIES + 0x123, 13);
        memory.putU8(FACILITIES + 0x124, 20);
        memory.putU8(FACILITIES + 0x125, 17);
        memory.putLong(CLUB + 0x150, EXTRA);
        memory.putU16(EXTRA, 0xB318);
        memory.putU8(EXTRA + 0x8B5, 9);

        ClubExporter.Facilities facilities = new ClubExporter().readFacilities(memory, CLUB);

        assertThat(facilities.training()).isEqualTo(18);
        assertThat(facilities.youth()).isEqualTo(13);
        assertThat(facilities.coaching()).isEqualTo(20);
        assertThat(facilities.recruitment()).isEqualTo(17);
        assertThat(facilities.corporate()).isEqualTo(9);
    }

    @Test
    void shiftedBudgetOffsetFailsValidationWhileCurrentPasses() throws Exception {
        FakeMemory memory = new FakeMemory();
        memory.putLong(CLUB + 0x150, EXTRA);
        memory.putU16(EXTRA, 0xB318);
        memory.putI32(EXTRA + 0x14, 1_000_000);
        memory.putI32(EXTRA + 0x7CC, 5_000_000);
        memory.putI32(EXTRA + 0x810, 2_000_000);
        memory.putI32(EXTRA + 0x7D0, -1_500_000_000);
        memory.putI32(EXTRA + 0x814, 1_000_000);

        ClubExporter.Finance finance = new ClubExporter().readFinance(memory, CLUB);
        assertThat(finance.transferBudget()).isEqualTo(5_000_000L);
        assertThatNoException().isThrownBy(() -> PlayerSnapshotValidator.validateClubs(
                List.of(clubRow(finance.transferBudget(), finance.payrollBudget())), 1));

        ClubRecordLayout base = ClubRecordLayout.current();
        ClubRecordLayout drifted = new ClubRecordLayout(
                base.teamClubRel(),
                base.teamCompetitionRel(),
                base.teamReputationRel(),
                base.competitionGenderFlagRel(),
                base.clubFinanceBlockRel(),
                base.clubFacilitiesBlockRel(),
                base.clubBalanceRel(),
                base.clubTransferBudgetRel() + 4,
                base.clubPayrollBudgetRel(),
                base.clubCorporateFacilitiesRel(),
                base.clubTrainingFacilitiesRel(),
                base.clubYouthFacilitiesRel(),
                base.clubYouthCoachingRel(),
                base.clubYouthRecruitmentRel());
        ClubExporter.Finance driftedFinance = new ClubExporter(drifted).readFinance(memory, CLUB);
        assertThatThrownBy(() -> PlayerSnapshotValidator.validateClubs(List.of(
                        clubRow(driftedFinance.transferBudget(), driftedFinance.payrollBudget())), 1))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("transferBudget=-1500000000");
    }

    private static Map<String, Object> clubRow(long transferBudget, long payrollBudget) {
        Map<String, Object> row = new HashMap<>();
        row.put("name", "Test FC");
        row.put("reputation", 7500);
        row.put("transferBudget", transferBudget);
        row.put("payrollBudget", payrollBudget);
        return row;
    }

    private static final class FakeMemory implements ProcessMemoryReader {
        private final Map<Long, Byte> bytes = new HashMap<>();

        void putLong(long address, long value) {
            put(address, ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
        }

        void putU16(long address, int value) {
            put(address, ByteBuffer.allocate(Short.BYTES).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array());
        }

        void putI32(long address, int value) {
            put(address, ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
        }

        void putU8(long address, int value) {
            bytes.put(address, (byte) value);
        }

        private void put(long address, byte[] value) {
            for (int index = 0; index < value.length; index++) {
                bytes.put(address + index, value[index]);
            }
        }

        @Override
        public int pid() {
            return 1;
        }

        @Override
        public byte[] readBytes(long address, int size) throws IOException {
            byte[] value = new byte[size];
            for (int index = 0; index < size; index++) {
                Byte next = bytes.get(address + index);
                if (next == null) {
                    throw new IOException("unmapped test address 0x" + Long.toHexString(address + index));
                }
                value[index] = next;
            }
            return value;
        }

        @Override
        public List<MemoryRegion> maps() {
            return List.of();
        }

        @Override
        public void close() {
        }
    }
}
