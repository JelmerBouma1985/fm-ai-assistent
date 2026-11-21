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
    void coefficientAndFinancialFallbacksPreserveBothWarningsAndClearOnlyOptionalFields() throws Exception {
        var memory = coefficientMemory();
        memory.bytes.remove(0x71001000L);
        var capture = new ClubIdentitySnapshot(memory, 0x180000000L,
                com.github.fmaiassistent.memory.ClubIdentityLayout.current(),
                com.github.fmaiassistent.memory.ClubUefaCoefficientLayout.current(),
                com.github.fmaiassistent.memory.ClubFinancialLoanLayout.current());
        memory.putLong(FACILITIES + 0x50, 0x72000000L);
        assertThatNoException().isThrownBy(capture::verify);
        assertThat(capture.coefficientFailure()).isNotBlank();
        assertThat(capture.warnings()).singleElement().asString().contains("Detailed club finances unavailable");
        assertThat(capture.records()).hasSize(2).allSatisfy(record -> {
            assertThat(record.uefaCoefficientHex()).isNull();
            assertThat(record.financialLoansHex()).isNull();
            assertThat(record.financialLoanClock()).isNull();
            assertThat(record.kind()).isEqualTo("CLUB");
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"valid", "read", "runtime", "clock-read", "clock-invalid", "layout", "loans", "ledger", "competition", "history", "reread", "reread-io", "reread-runtime"})
    void detailedFinanceFailuresDiscardPartialCaptureWithoutLosingClubsOrCoefficients(String failure) throws Exception {
        var memory = coefficientMemory();
        addDetailedFinances(memory);
        var loans = com.github.fmaiassistent.memory.ClubFinancialLoanLayout.current();
        var ledger = com.github.fmaiassistent.memory.ClubFinancialLedgerLayout.current();
        var history = com.github.fmaiassistent.memory.ClubFinancialHistoryLayout.current();
        long secondLoanHeader = FACILITIES + 0x1000 + loans.vectorRel();
        long secondFinance = 0x73001000L;
        ProcessMemoryReader source = memory;
        if (failure.equals("read")) memory.bytes.remove(secondLoanHeader);
        if (failure.equals("runtime") || failure.equals("reread-runtime")) source = org.mockito.Mockito.spy(memory);
        if (failure.equals("runtime")) org.mockito.Mockito.doThrow(new IllegalStateException("Finance read failed"))
                .when(source).readBytes(secondLoanHeader, loans.headerBytes());
        if (failure.equals("clock-read")) memory.bytes.remove(0x180000000L + loans.clockRva());
        if (failure.equals("clock-invalid")) memory.putI32(0x180000000L + loans.clockRva(), 0);
        if (failure.equals("layout")) loans = shiftedLoanLayout(loans);
        if (failure.equals("loans")) memory.putLong(secondLoanHeader, 0x75000000L);
        if (failure.equals("ledger")) memory.putLong(secondFinance, 0x180000000L + ledger.fullVtableRva() + 8);
        if (failure.equals("competition")) memory.putLong(secondFinance + ledger.blocksRel(), 0x75000000L);
        if (failure.equals("history")) memory.putU8(secondFinance + history.summaryRel() + history.countRel(), history.capacity() + 1);

        var capture = new ClubIdentitySnapshot(source, 0x180000000L,
                com.github.fmaiassistent.memory.ClubIdentityLayout.current(),
                com.github.fmaiassistent.memory.ClubUefaCoefficientLayout.current(), loans, ledger,
                com.github.fmaiassistent.memory.ClubFinancialCompetitionLayout.current(),
                com.github.fmaiassistent.memory.CompetitionNameLayout.current(), history);
        if (failure.startsWith("reread")) {
            // Both clubs already have all four payloads; a late failure must clear all of them.
            assertThat(capture.records()).allSatisfy(record -> {
                assertThat(record.financialLoansHex()).isNotNull();
                assertThat(record.financialLedgerHex()).isNotNull();
                assertThat(record.financialCompetitionsHex()).isNotNull();
                assertThat(record.financialHistoryHex()).isNotNull();
            });
            if (failure.equals("reread")) memory.putLong(secondLoanHeader, 0x75000000L);
            if (failure.equals("reread-io")) memory.bytes.remove(secondLoanHeader);
            if (failure.equals("reread-runtime")) org.mockito.Mockito.doThrow(new IllegalStateException("Finance reread failed"))
                    .when(source).readBytes(secondLoanHeader, loans.headerBytes());
        }
        assertThatNoException().isThrownBy(capture::verify);
        assertThat(capture.coefficientFailure()).isNull();
        assertThat(capture.records()).hasSize(2).allSatisfy(record -> {
            assertThat(record.uniqueId()).isBetween(100L, 101L);
            assertThat(record.kind()).isEqualTo("CLUB");
            assertThat(record.uefaCoefficientHex()).isNotNull();
            assertThat(record.uefaRuleVersion()).isNotNull();
            if (failure.equals("valid")) {
                assertThat(record.financialLoansHex()).isEqualTo("");
                assertThat(record.financialLedgerHex()).isNotNull();
                assertThat(record.financialCompetitionsHex()).isNotNull();
                assertThat(record.financialHistoryHex()).isNotNull();
            } else {
                assertThat(record.financialLoansHex()).isNull();
                assertThat(record.financialLoanClock()).isNull();
                assertThat(record.financialLedgerHex()).isNull();
                assertThat(record.financialLedgerClock()).isNull();
                assertThat(record.financialCompetitionsHex()).isNull();
                assertThat(record.financialHistoryHex()).isNull();
            }
        });
        if (failure.equals("valid")) {
            assertThat(capture.warnings()).isEmpty();
            assertThat(capture.competitionNames()).isEmpty();
        } else {
            assertThat(capture.warnings()).singleElement().asString().contains("Detailed club finances unavailable");
            assertThat(capture.competitionNames()).isNull();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"initial-prefix", "prefix", "registry"})
    void optionalFinancialCaptureStillRejectsCoreClubIdentityChanges(String failure) throws Exception {
        var memory = coefficientMemory();
        if (failure.equals("initial-prefix")) {
            memory.putI32(CLUB + 8, 99);
            assertThatThrownBy(() -> new ClubIdentitySnapshot(memory, 0x180000000L,
                    com.github.fmaiassistent.memory.ClubIdentityLayout.current(),
                    com.github.fmaiassistent.memory.ClubUefaCoefficientLayout.current(),
                    com.github.fmaiassistent.memory.ClubFinancialLoanLayout.current()))
                    .isInstanceOf(PlayerSnapshotValidator.ClubIdentityChangedException.class);
            return;
        }
        var capture = new ClubIdentitySnapshot(memory, 0x180000000L,
                com.github.fmaiassistent.memory.ClubIdentityLayout.current(),
                com.github.fmaiassistent.memory.ClubUefaCoefficientLayout.current(),
                com.github.fmaiassistent.memory.ClubFinancialLoanLayout.current());
        if (failure.equals("prefix")) memory.putI32(CLUB + 12, 999);
        else memory.putLong(0x60002000L, CLUB + 0x1000);
        assertThatThrownBy(capture::verify).isInstanceOf(PlayerSnapshotValidator.ClubIdentityChangedException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"capture,cancel", "capture,interrupt", "verify,cancel", "verify,interrupt"})
    void optionalFinancialFailuresDoNotSwallowCancellationOrInterruption(String phase, String failure) throws Exception {
        var memory = coefficientMemory();
        var source = org.mockito.Mockito.spy(memory);
        var loans = com.github.fmaiassistent.memory.ClubFinancialLoanLayout.current();
        long header = FACILITIES + 0x1000 + loans.vectorRel();
        Exception error = failure.equals("cancel") ? new java.util.concurrent.CancellationException("Stopped") : new IOException("Interrupted");
        ClubIdentitySnapshot capture = phase.equals("verify") ? new ClubIdentitySnapshot(source, 0x180000000L,
                com.github.fmaiassistent.memory.ClubIdentityLayout.current(),
                com.github.fmaiassistent.memory.ClubUefaCoefficientLayout.current(), loans) : null;
        org.mockito.Mockito.doAnswer(invocation -> {
            if (failure.equals("interrupt")) Thread.currentThread().interrupt();
            throw error;
        }).when(source).readBytes(header, loans.headerBytes());
        try {
            if (capture == null) assertThatThrownBy(() -> new ClubIdentitySnapshot(source, 0x180000000L,
                    com.github.fmaiassistent.memory.ClubIdentityLayout.current(),
                    com.github.fmaiassistent.memory.ClubUefaCoefficientLayout.current(), loans)).isSameAs(error);
            else assertThatThrownBy(capture::verify).isSameAs(error);
        } finally { Thread.interrupted(); }
    }

    @Test
    void optionalFinanceLayoutFactoryFailureKeepsCoreIdentityAndVerifiedCoefficients() throws Exception {
        var memory = coefficientMemory();
        var warnings = new java.util.ArrayList<String>();
        String profile = com.github.fmaiassistent.memory.NativeModuleProfile.FM27_SHA256;
        try (var layouts = org.mockito.Mockito.mockStatic(com.github.fmaiassistent.memory.ClubFinancialLoanLayout.class)) {
            layouts.when(() -> com.github.fmaiassistent.memory.ClubFinancialLoanLayout.forModule(profile))
                    .thenThrow(new IllegalArgumentException("Unverified finance root"));
            var capture = ClubExporter.captureIdentitySnapshot(memory, 0x180000000L, profile, warnings);
            capture.verify();
            assertThat(capture.coefficientFailure()).isNull();
            assertThat(capture.records()).hasSize(2).allSatisfy(record -> {
                assertThat(record.kind()).isEqualTo("CLUB");
                assertThat(record.uefaCoefficientHex()).isNotNull();
                assertThat(record.financialLoansHex()).isNull();
                assertThat(record.financialLoanClock()).isNull();
                assertThat(record.financialLedgerHex()).isNull();
                assertThat(record.financialLedgerClock()).isNull();
                assertThat(record.financialCompetitionsHex()).isNull();
                assertThat(record.financialHistoryHex()).isNull();
            });
            assertThat(warnings).containsExactly(ClubIdentitySnapshot.FINANCIAL_WARNING);
        }
    }

    @Test
    void requiredCoreIdentityLayoutFactoryFailureRemainsFatal() throws Exception {
        var memory = coefficientMemory();
        String profile = com.github.fmaiassistent.memory.NativeModuleProfile.FM27_SHA256;
        var error = new IllegalArgumentException("Unverified core identity root");
        var warnings = new java.util.ArrayList<String>();
        try (var layouts = org.mockito.Mockito.mockStatic(com.github.fmaiassistent.memory.ClubIdentityLayout.class)) {
            layouts.when(() -> com.github.fmaiassistent.memory.ClubIdentityLayout.forModule(profile)).thenThrow(error);
            assertThatThrownBy(() -> ClubExporter.captureIdentitySnapshot(memory, 0x180000000L, profile, warnings))
                    .isSameAs(error);
            assertThat(warnings).isEmpty();
        }
    }

    private static void addDetailedFinances(FakeMemory memory) {
        var ledger = com.github.fmaiassistent.memory.ClubFinancialLedgerLayout.current();
        for (int index = 0; index < 2; index++) {
            long finance = 0x73000000L + index * 0x1000L;
            memory.put(finance, new byte[ledger.fullBytes()]);
            memory.putLong(finance, 0x180000000L + ledger.fullVtableRva());
            memory.putLong(CLUB + index * 0x1000L + ledger.financeRel(), finance);
        }
    }

    private static com.github.fmaiassistent.memory.ClubFinancialLoanLayout shiftedLoanLayout(
            com.github.fmaiassistent.memory.ClubFinancialLoanLayout layout) throws Exception {
        var components = layout.getClass().getRecordComponents();
        var types = java.util.Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
        Object[] values = new Object[components.length];
        for (int index = 0; index < values.length; index++) {
            values[index] = components[index].getAccessor().invoke(layout);
            if (components[index].getName().equals("recordBytes")) values[index] = layout.recordBytes() + 1;
        }
        return (com.github.fmaiassistent.memory.ClubFinancialLoanLayout) layout.getClass().getDeclaredConstructor(types).newInstance(values);
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
