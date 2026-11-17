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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"valid", "read", "validation", "runtime", "rule", "reread"})
    void coefficientFailuresKeepNativeClubsAndFinancialLoans(String failure) throws Exception {
        var memory = coefficientMemory();
        var uefa = com.github.fmaiassistent.memory.ClubUefaCoefficientLayout.current();
        long secondCoefficient = 0x71001000L;
        ProcessMemoryReader source = memory;
        if (failure.equals("read")) memory.bytes.remove(secondCoefficient);
        if (failure.equals("validation")) memory.putU8(secondCoefficient, 11);
        if (failure.equals("rule")) memory.bytes.remove(0x180000000L + uefa.ruleVersionRva());
        if (failure.equals("runtime")) {
            source = org.mockito.Mockito.spy(memory);
            org.mockito.Mockito.doThrow(new IllegalStateException("coefficient read failed"))
                    .when(source).readBytes(secondCoefficient, uefa.objectBytes());
        }
        var capture = new ClubIdentitySnapshot(source, 0x180000000L,
                com.github.fmaiassistent.memory.ClubIdentityLayout.current(), uefa,
                com.github.fmaiassistent.memory.ClubFinancialLoanLayout.current());
        if (failure.equals("reread")) memory.putU8(secondCoefficient, 1);
        capture.verify();
        assertThat(capture.records()).hasSize(2).allSatisfy(record -> {
            assertThat(record.financialLoansHex()).isEqualTo("");
            assertThat(record.financialLoanClock()).isEqualTo((2026 << 16) | 187);
            assertThat(record.kind()).isEqualTo("CLUB");
            if (failure.equals("valid")) assertThat(record.uefaCoefficientHex()).isNotNull();
            else {
                assertThat(record.uefaCoefficientHex()).isNull();
                assertThat(record.uefaRuleVersion()).isNull();
            }
        });
        if (failure.equals("valid")) assertThat(capture.coefficientFailure()).isNull();
        else assertThat(capture.coefficientFailure()).isNotBlank();
    }

    @Test
    void coefficientFallbackStillRejectsFinancialContextDrift() throws Exception {
        var memory = coefficientMemory();
        memory.bytes.remove(0x71001000L);
        var capture = new ClubIdentitySnapshot(memory, 0x180000000L,
                com.github.fmaiassistent.memory.ClubIdentityLayout.current(),
                com.github.fmaiassistent.memory.ClubUefaCoefficientLayout.current(),
                com.github.fmaiassistent.memory.ClubFinancialLoanLayout.current());
        memory.putLong(FACILITIES + 0x50, 0x72000000L);
        assertThatThrownBy(capture::verify).isInstanceOf(IOException.class);
    }

    private static FakeMemory coefficientMemory() {
        var memory = new FakeMemory();
        var identity = com.github.fmaiassistent.memory.ClubIdentityLayout.current();
        var uefa = com.github.fmaiassistent.memory.ClubUefaCoefficientLayout.current();
        long base = 0x180000000L, descriptor = 0x60000000L, header = 0x60001000L, pointers = 0x60002000L;
        memory.putLong(base + identity.registryRva(), descriptor);
        memory.putLong(descriptor + identity.registryHeader(), header);
        memory.putLong(header, pointers); memory.putLong(header + 8, pointers + 16); memory.putLong(header + 16, pointers + 16);
        memory.putU16(base + uefa.ruleVersionRva(), 1684);
        memory.putI32(base + com.github.fmaiassistent.memory.ClubFinancialLoanLayout.current().clockRva(), (2026 << 16) | 187);
        for (int i = 0; i < 2; i++) {
            long club = CLUB + i * 0x1000L, data = FACILITIES + i * 0x1000L, nation = EXTRA + i * 0x1000L;
            long coefficient = 0x71000000L + i * 0x1000L;
            memory.put(club, new byte[0x200]); memory.put(data, new byte[0x100]); memory.put(nation, new byte[0x120]);
            memory.putLong(pointers + i * 8L, club); memory.putLong(club, base + identity.clubVtable());
            memory.putI32(club + 8, i); memory.putI32(club + 12, i + 100);
            memory.putLong(club + uefa.clubDataRel(), data); memory.putLong(club + uefa.nationRel(), nation);
            memory.putLong(nation + uefa.nationContextRel(), 0x70000000L);
            memory.putLong(data + uefa.coefficientRel(), coefficient); memory.put(coefficient, new byte[uefa.objectBytes()]);
        }
        return memory;
    }

    @Test
    void typedCoefficientAndFinanceCaptureSupportsWindowsAndLinux() {
        assertThat(ClubExporter.supportsTypedNativeCapture(ProcessMemoryReader.Platform.WINDOWS)).isTrue();
        assertThat(ClubExporter.supportsTypedNativeCapture(ProcessMemoryReader.Platform.LINUX)).isTrue();
        assertThat(ClubExporter.supportsTypedNativeCapture(ProcessMemoryReader.Platform.UNKNOWN)).isFalse();
    }

    @Test
    void readsFm27FacilityRatingsFromTheClubSubstructures() throws Exception {
        FakeMemory memory = new FakeMemory();
        memory.putLong(CLUB + 0x100, FACILITIES);
        memory.putU8(FACILITIES + 0x120, 18);
        memory.putU8(FACILITIES + 0x12B, 13);
        memory.putU8(FACILITIES + 0x12C, 20);
        memory.putU8(FACILITIES + 0x12D, 17);
        memory.putLong(CLUB + 0x150, EXTRA);
        memory.putU16(EXTRA, 0x0CC8);
        memory.putU8(EXTRA + 0x8A9, 9);

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
        memory.putU16(EXTRA, 0x0CC8);
        memory.putI32(EXTRA + 0x0C, 1_000_000);
        memory.putI32(EXTRA + 0x7C4, 5_000_000);
        memory.putI32(EXTRA + 0x808, 2_000_000);
        memory.putI32(EXTRA + 0x7C8, -1_500_000_000);
        memory.putI32(EXTRA + 0x80C, 1_000_000);

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
