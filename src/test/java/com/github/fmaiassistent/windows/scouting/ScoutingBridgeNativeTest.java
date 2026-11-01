package com.github.fmaiassistent.windows.scouting;

import com.github.fmaiassistent.memory.ScoutingReportLayout;
import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Executes the actual shipped x64 image in this test JVM against mock FM objects.
 * No FM process, TCC, Cheat Engine or write privileges are involved. */
@EnabledOnOs(OS.WINDOWS)
class ScoutingBridgeNativeTest {
    @Test
    void actualFactoryAbiRestoresAttributesAndNativeRegistrationReplacesPriorReport() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertThat(fixture.transaction()).isZero();
            assertThat(fixture.inputJudgements).containsExactly(0x5046);
            assertThat(fixture.staff.getShort(0x2C)).isEqualTo((short) 0x5046);
            assertThat(fixture.control.getLong(29 * 8L)).isEqualTo(1);
            assertThat(fixture.control.getLong(31 * 8L)).isEqualTo(1);
            assertThat(fixture.report.getByte(0x3F)).isEqualTo((byte) 100);
            assertThat(fixture.report.getByte(0x40)).isEqualTo((byte) 5);

            fixture.control.setLong(23 * 8L, 1);
            fixture.control.setLong(24 * 8L, 0);
            assertThat(fixture.transaction()).isZero();
            assertThat(fixture.inputJudgements).containsExactly(0x5046, 0x6464);
            assertThat(fixture.staff.getShort(0x2C)).isEqualTo((short) 0x5046);
            assertThat(fixture.control.getLong(31 * 8L)).isEqualTo(2);
            assertThat(fixture.control.getLong(26 * 8L)).isEqualTo(1);
            assertThat(fixture.control.getLong(30 * 8L)).isEqualTo(20);
            assertThat(fixture.registerCalls).isEqualTo(2);
        }
    }

    @Test
    void actualNullFactoryFailureRestoresPerfectModeAttributes() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.control.setLong(23 * 8L, 1);
            fixture.failFactory = true;
            assertThat(fixture.transaction()).isEqualTo(96);
            assertThat(fixture.inputJudgements).containsExactly(0x6464);
            assertThat(fixture.staff.getShort(0x2C)).isEqualTo((short) 0x5046);
            assertThat(fixture.control.getLong(29 * 8L)).isEqualTo(1);
            assertThat(fixture.registerCalls).isZero();
        }
    }

    @Test
    void actualTypedPoolSelectionRejectsMissingDescriptorBeforeFactoryOrAttributeWrites() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.poolTable.setLong(ScoutingReportLayout.current().recruitmentEntryBytes() * 8L, 0);
            fixture.slot(23, 1);
            assertThat(fixture.transaction()).isEqualTo(104);
            assertThat(fixture.inputJudgements).isEmpty();
            assertThat(fixture.staff.getShort(0x2C)).isEqualTo((short) 0x5046);
            assertThat(fixture.registerCalls).isZero();
        }
    }

    @Test
    void actualTypedPoolSelectionRejectsTruncatedDescriptorTableBeforeFactory() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var layout = ScoutingReportLayout.current();
            fixture.poolManager.setLong(layout.recruitmentPoolsEndRel(), address(fixture.poolTable) + 8);
            fixture.slot(23, 1);
            assertThat(fixture.transaction()).isEqualTo(104);
            assertThat(fixture.inputJudgements).isEmpty();
            assertThat(fixture.registerCalls).isZero();
        }
    }

    @Test
    void actualOneShotWindowCallbackRestoresHandlerBeforeFactoryAndCountsForwardedFrames() throws Exception {
        try (Fixture fixture = new Fixture()) {
            final long original = 0x7FF123456789L;
            long[] current = {original};
            F2 get = (hwnd, index) -> current[0];
            F3 set = (hwnd, index, value) -> { long previous = current[0]; current[0] = value; return previous; };
            F5 forward = (old, hwnd, msg, wp, lp) -> {
                assertThat(old).isEqualTo(original);
                assertThat(fixture.control.getLong(56 * 8L)).isEqualTo(1);
                return 42;
            };
            F0 thread = () -> 12345;
            fixture.keep(get, set, forward, thread);
            fixture.slot(1, 0x1234); fixture.slot(2, pointer(get)); fixture.slot(3, pointer(set));
            fixture.slot(4, pointer(forward)); fixture.slot(5, pointer(thread)); fixture.slot(6, 12345);
            fixture.slot(7, 0x464d414953434f55L); fixture.slot(8, 0x8351);
            assertThat(fixture.call("bridge_install", address(fixture.control))).isEqualTo(1);
            Function proc = Function.getFunction(new Pointer(fixture.code + fixture.image.offset("bridge_proc")));
            assertThat(proc.invokeLong(new Object[]{0x1234L, 0x100, 4L, 5L})).isEqualTo(42);
            assertThat(fixture.control.getLong(56 * 8L)).isZero();
            assertThat(proc.invokeLong(new Object[]{0x1234L, 0x8351, fixture.control.getLong(7 * 8L),
                    address(fixture.control)})).isEqualTo(1);
            assertThat(current[0]).isEqualTo(original);
            assertThat(fixture.control.getLong(9 * 8L)).isEqualTo(2);
            assertThat(fixture.control.getLong(54 * 8L)).isEqualTo(1);
            assertThat(fixture.control.getLong(56 * 8L)).isZero();
            assertThat(fixture.call("bridge_uninstall", address(fixture.control))).isEqualTo(1);
        }
    }

    private static long pointer(Callback callback) { return Pointer.nativeValue(CallbackReference.getFunctionPointer(callback)); }
    private static long address(Pointer memory) { return Pointer.nativeValue(memory); }
    private interface F0 extends Callback { long invoke(); }
    private interface F1 extends Callback { long invoke(long a); }
    private interface F2 extends Callback { long invoke(long a, long b); }
    private interface F3 extends Callback { long invoke(long a, long b, long c); }
    private interface F4 extends Callback { long invoke(long a, long b, long c, long d); }
    private interface F5 extends Callback { long invoke(long a, long b, long c, long d, long e); }
    private interface F7 extends Callback { long invoke(long a, long b, long c, long d, long e, long f, long g); }
    private interface F12 extends Callback { long invoke(long a, long b, long c, long d, long e, long f, long g, long h,
                                                       long i, long j, long k, long l); }
    private interface K extends StdCallLibrary {
        K INSTANCE = Native.load("kernel32", K.class);
        Pointer VirtualAlloc(Pointer address, long size, int allocation, int protection);
        boolean VirtualFree(Pointer address, long size, int freeType);
    }

    private static final class Fixture implements AutoCloseable {
        final ScoutingBridgeImage image = ScoutingBridgeImage.load();
        final Memory control = new Memory(85 * 8);
        final Memory base = new Memory(32), human = new Memory(0x500), staff = new Memory(0x300);
        final Memory player = new Memory(0x500), contract = new Memory(0x100), team = new Memory(0x100);
        final Memory report = new Memory(0x48), extra = new Memory(0x1B0), vector = new Memory(64);
        final Memory humanData = new Memory(0x1000), recruitmentVector = new Memory(64), recruitmentEntry = new Memory(0x58);
        final Memory recruitmentTag = new Memory(0x48);
        final Memory poolManager = new Memory(0x80), poolDescriptor = new Memory(0x80);
        final Memory poolTable = new Memory((0x58 + 1) * 8);
        final List<Callback> callbacks = new ArrayList<>();
        final List<Integer> inputJudgements = new ArrayList<>();
        final long code;
        int registerCalls;
        boolean failFactory;
        Fixture() throws Exception {
            for (Memory memory : List.of(control, base, human, staff, player, contract, team, report, extra, vector,
                    humanData, recruitmentVector, recruitmentEntry, recruitmentTag,
                    poolManager, poolDescriptor, poolTable)) memory.clear();
            Pointer allocation = K.INSTANCE.VirtualAlloc(null, image.size(), 0x3000, 0x40);
            assertThat(allocation).isNotNull(); code = address(allocation);
            byte[] bytes = image.relocate(code, address(control)); allocation.write(0, bytes, 0, bytes.length);
            var layout = ScoutingReportLayout.current();
            var scout = new WindowsScoutingWriter.Scout(22, address(staff) + 0x100, address(staff), 70, 80, 14, "Scout");
            var context = new WindowsScoutingWriter.Context(address(human) + 0x440, 33, address(human),
                    address(team), 7777, 100, scout, java.util.Map.of());
            var target = new WindowsScoutingWriter.Target(44, address(player) + 0x288, address(player), 4);
            long[] state = WindowsScoutingWriter.control(context, target, false, address(base), layout);
            for (int index = 0; index < state.length; index++) slot(index, state[index]);
            slot(32, 0); slot(33, 8); slot(79, 16); base.setInt(16, -1000);
            slot(70, address(poolManager) - address(base));
            poolManager.setLong(layout.recruitmentPoolsBeginRel(), address(poolTable));
            poolManager.setLong(layout.recruitmentPoolsEndRel(), address(poolTable) + poolTable.size());
            poolTable.setLong(layout.recruitmentEntryBytes() * 8L, address(poolDescriptor));
            base.setLong(0, context.manager()); base.setInt(8, 100);
            human.setInt(0x440 + 0x0C, 33); human.setLong(0x440 + 0xA8, address(contract));
            staff.setInt(0x100 + 0x0C, 22); staff.setLong(0x100 + 0xA8, address(contract));
            player.setInt(0x288 + 0x0C, 44); contract.setLong(0x10, address(team)); team.setLong(0x30, 7777);
            human.setLong(0x110, address(vector)); human.setLong(0x118, address(vector)); human.setLong(0x120, address(vector) + 64);
            human.setLong(layout.humanDataRel(), address(humanData));
            long recruitmentHeader = layout.recruitmentContextRel() + layout.recruitmentEntriesRel();
            humanData.setLong(recruitmentHeader, address(recruitmentVector));
            humanData.setLong(recruitmentHeader + 8, address(recruitmentVector));
            humanData.setLong(recruitmentHeader + 16, address(recruitmentVector) + 64);
            staff.setShort(0x2C, (short) 0x5046);
            F7 factory = (out, p, s, h, t, mode, coverage) -> {
                assertThat(List.of(p, s, h, t, mode, coverage)).containsExactly(address(player), address(staff),
                        address(human), address(team), 5L, 65535L);
                int raw = Short.toUnsignedInt(staff.getShort(0x2C)); inputJudgements.add(raw);
                if (failFactory) return out;
                new Pointer(out).setLong(0, address(report));
                report.setLong(0, target.person()); report.setLong(8, scout.person()); report.setLong(16, address(team));
                report.setLong(0x30, address(extra)); extra.setByte(0x84, (byte) ((raw & 0xFF) / 5));
                return out;
            };
            F2 register = (h, tag) -> {
                assertThat(staff.getShort(0x2C)).isEqualTo((short) 0x5046);
                assertThat(control.getLong(54 * 8L)).isIn(0L, 1L);
                vector.setLong(0, tag); human.setLong(0x118, address(vector) + 8); registerCalls++; return 0;
            };
            F4 lookup = (h, p, filter, flag) -> registerCalls == 0 ? 0 : address(report);
            F1 dispose = ignored -> 0;
            F2 allocate = (pool, bytesCount) -> {
                assertThat(pool).isEqualTo(address(poolDescriptor)).isNotEqualTo(address(poolManager));
                assertThat(bytesCount).isEqualTo(layout.recruitmentEntryBytes());
                return address(recruitmentEntry);
            };
            F12 initialize = (entry, p, h, result, tag, assignment, t, flags, kind, delivery, reason, id) -> {
                assertThat(List.of(entry, p, h, result, tag, assignment, t, flags, kind, delivery, reason, id))
                        .containsExactly(address(recruitmentEntry), address(player), address(human), 0L,
                                address(report), 0L, address(team), 0L, 1L, 0L, 2L, 0xFFFFFFFFL);
                assertThat(staff.getShort(0x2C)).isEqualTo((short) 0x5046);
                recruitmentTag.write(0, report.getByteArray(0, 0x48), 0, 0x48);
                recruitmentEntry.setLong(0, address(base) + layout.recruitmentEntryVtableRva());
                recruitmentEntry.setLong(layout.recruitmentEntryTagRel(), address(recruitmentTag));
                return 1;
            };
            F2 enroll = (contextAddress, ownedEntry) -> {
                assertThat(contextAddress).isEqualTo(address(humanData) + layout.recruitmentContextRel());
                assertThat(new Pointer(ownedEntry).getLong(0)).isEqualTo(address(recruitmentEntry));
                recruitmentVector.setLong(0, address(recruitmentEntry));
                humanData.setLong(recruitmentHeader + 8, address(recruitmentVector) + 8);
                new Pointer(ownedEntry).setLong(0, 0);
                return 0;
            };
            keep(factory, register, lookup, dispose, allocate, initialize, enroll);
            slot(45, pointer(factory) - address(base)); slot(46, pointer(register) - address(base));
            slot(47, pointer(lookup) - address(base)); slot(48, pointer(dispose) - address(base));
            slot(49, pointer(dispose) - address(base)); slot(50, pointer(dispose) - address(base));
            slot(60, pointer(allocate) - address(base)); slot(71, pointer(initialize) - address(base));
            slot(72, pointer(register) - address(base)); slot(73, pointer(enroll) - address(base));
        }
        void keep(Callback... values) { callbacks.addAll(List.of(values)); }
        void slot(int index, long value) { control.setLong(index * 8L, value); }
        long transaction() throws Exception { return call("bridge_transaction"); }
        long call(String name, Object... parameters) throws Exception {
            return Function.getFunction(new Pointer(code + image.offset(name))).invokeLong(parameters);
        }
        @Override public void close() {
            K.INSTANCE.VirtualFree(new Pointer(code), 0, 0x8000);
            for (Memory memory : List.of(control, base, human, staff, player, contract, team, report, extra, vector,
                    humanData, recruitmentVector, recruitmentEntry, recruitmentTag,
                    poolManager, poolDescriptor, poolTable)) memory.close();
        }
    }
}
