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
        memory.putI32(EXTRA + 0x7D0, -50_000_000);
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
                .hasMessageContaining("transferBudget=-50000000");
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
