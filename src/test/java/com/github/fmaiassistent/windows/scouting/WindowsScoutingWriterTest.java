package com.github.fmaiassistent.windows.scouting;

import com.github.fmaiassistent.exporter.PlayerSnapshotValidator;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import com.github.fmaiassistent.memory.ScoutingReportLayout;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WindowsScoutingWriterTest {
    @Test
    void picksCombinedBestWithBalancedJudgementAndStableTieBreak() throws Exception {
        var best = new WindowsScoutingWriter.Scout(2, 10, 20, 90, 90, 14, "Best");
        var uneven = new WindowsScoutingWriter.Scout(3, 10, 20, 100, 80, 44, "Uneven");
        var weaker = new WindowsScoutingWriter.Scout(1, 10, 20, 85, 85, 44, "Weaker");
        assertThat(WindowsScoutingWriter.bestScout(List.of(weaker, uneven, best))).isEqualTo(best);
        assertThatThrownBy(() -> WindowsScoutingWriter.bestScout(List.of()))
                .isInstanceOf(IOException.class).hasMessageContaining("no supported scout");
    }

    @Test
    void carriesModeAndAllNativeOffsetsIntoCallbackWithoutChangingBestScout() throws Exception {
        var scout = new WindowsScoutingWriter.Scout(22, 1000, 744, 70, 80, 14, "Scout");
        var context = new WindowsScoutingWriter.Context(5000, 33, 3912, 6000, 7000, 100,
                scout, java.util.Map.of());
        var target = new WindowsScoutingWriter.Target(44, 8000, 7352, 4);
        var layout = ScoutingReportLayout.current();
        long[] ordinary = WindowsScoutingWriter.control(context, target, false, 0x7FFB87F40000L, layout);
        long[] perfect = WindowsScoutingWriter.control(context, target, true, 0x7FFB87F40000L, layout);
        assertThat(ordinary[23]).isZero();
        assertThat(perfect[23]).isEqualTo(1);
        assertThat(perfect[17]).isEqualTo(ordinary[17]).isEqualTo(scout.complete());
        assertThat(perfect[45]).isEqualTo(layout.factoryRva());
        assertThat(perfect[46]).isEqualTo(layout.registerRva());
        assertThat(perfect[39]).isEqualTo(layout.judgementRel());
        assertThat(perfect[51]).isEqualTo(layout.humanDisplacement());
    }

    @Test
    void rejectsSamePlausibleFixtureWhenScoutingLayoutShifts() throws Exception {
        var current = ScoutingReportLayout.current();
        var shifted = new ScoutingReportLayout(current.managerRva(), current.clockRva(), current.peopleRva(),
                current.peopleHeaderRel(), current.uidRel(), current.indexRel(), current.employmentRel(),
                current.contractTeamRel(), current.teamClubRel(), current.staffDisplacement(),
                current.playerDisplacement(), current.playerStaffDisplacement(), current.humanDisplacement(),
                current.judgementRel(), current.jobRel(), current.reportsRel(), current.extraRel(),
                current.percentRel() + 1, current.categoryRel() + 1, current.extraJcaRel(),
                current.factoryRva(), current.registerRva(), current.lookupRva(), current.extraDestructorRva(),
                current.extraFreeRva(), current.tagFreeRva(), current.rttiDisplacementRel(),
                current.playerVbtableRel(), current.playerVbtablePersonRel(), current.tagPersonRel(),
                current.tagStaffRel(), current.tagTeamRel(), current.recruitmentEntryAllocateRva(),
                current.humanDataRel(), current.recruitmentContextRel(), current.recruitmentEntriesRel(),
                current.recruitmentEntryTagRel(), current.recruitmentEntryVtableRva(), current.recruitmentEntryPoolRva(),
                current.recruitmentEntryInitRva(), current.recruitmentEntryDeleteRva(), current.recruitmentEntryAddRva(),
                current.recruitmentEntryBytes(), current.recruitmentEntryDateRel(), current.recruitmentEntryIdRel(),
                current.recruitmentEntryDefaultsRel(), current.recruitmentEntryDeliveryRel(), current.recruitmentEntryPoolGuardRva(),
                current.recruitmentPoolsBeginRel(), current.recruitmentPoolsEndRel(), current.recruitmentPoolPointerBytes());
        byte[] tag = new byte[0x48];
        tag[current.percentRel()] = 100; tag[current.categoryRel()] = 5;
        PlayerSnapshotValidator.validateScoutingReportLayout(current);
        assertThat(Byte.toUnsignedInt(tag[current.percentRel()])).isEqualTo(100);
        assertThatThrownBy(() -> PlayerSnapshotValidator.validateScoutingReportLayout(shifted))
                .isInstanceOf(IOException.class).hasMessageContaining("anchor failed");
    }

    @Test
    void completionRequiresRestoredJudgementAndCorrectPlayerOwner() throws Exception {
        var reader = mock(ProcessMemoryReader.class);
        var layout = ScoutingReportLayout.current();
        var scout = new WindowsScoutingWriter.Scout(22, 1000, 744, 70, 80, 14, "Scout");
        var context = new WindowsScoutingWriter.Context(5000, 33, 3912, 6000, 7000, 100,
                scout, java.util.Map.of());
        var target = new WindowsScoutingWriter.Target(44, 8000, 7352, 4);
        long[] state = WindowsScoutingWriter.control(context, target, true, 0, layout);
        state[9] = 2; state[54] = 1; state[29] = 1; state[24] = 9000; state[28] = 0x5046; state[31] = 1; state[30] = 20;
        when(reader.readU16(scout.complete() + layout.judgementRel())).thenReturn(0x5046);
        when(reader.readU64(9000)).thenReturn(target.person());
        when(reader.readU64(9008)).thenReturn(scout.person());
        when(reader.readU64(9016)).thenReturn(context.team());
        when(reader.readU8(9000 + layout.percentRel())).thenReturn(100);
        when(reader.readU8(9000 + layout.categoryRel())).thenReturn(5);
        when(reader.readU64(9000 + layout.extraRel())).thenReturn(10000L);
        when(reader.readU8(10000 + layout.extraJcaRel())).thenReturn(20);
        state[69] = 1; state[68] = 12000;
        when(reader.readU64(12000 + layout.recruitmentEntryTagRel())).thenReturn(13000L);
        when(reader.readU64(13000 + layout.extraRel())).thenReturn(14000L);
        when(reader.readU64(13000 + layout.tagPersonRel())).thenReturn(target.person());
        when(reader.readU64(13000 + layout.tagStaffRel())).thenReturn(scout.person());
        when(reader.readU64(13000 + layout.tagTeamRel())).thenReturn(context.team());
        when(reader.readU8(13000 + layout.percentRel())).thenReturn(100);
        when(reader.readU8(14000 + layout.extraJcaRel())).thenReturn(20);
        WindowsScoutingWriter.verifyCompleted(reader, context, target, state, layout);
        when(reader.readU16(scout.complete() + layout.judgementRel())).thenReturn(0x6464);
        assertThatThrownBy(() -> WindowsScoutingWriter.verifyCompleted(reader, context, target, state, layout))
                .isInstanceOf(IOException.class).hasMessageContaining("verification failed");
    }

    @Test
    void cancellationBeforeResolutionDoesNotOpenAnyNativeSessionOrEmitProgress() throws Exception {
        var writer = new WindowsScoutingWriter() { @Override public boolean isSupported() { return true; } };
        var result = writer.scout(-1, List.of(123L), true,
                (done, total) -> { throw new AssertionError("Cancelled before native resolution"); }, () -> true);
        assertThat(result.cancelled()).isTrue();
        assertThat(result.created()).isZero();
        assertThat(result.updated()).isZero();
    }

    @Test
    void resolvesPureAndHybridPlayerArgumentsThroughNativeVirtualPersonGetter() throws Exception {
        var reader = mock(ProcessMemoryReader.class);
        var layout = ScoutingReportLayout.current();
        long person = 10000, vtable = 20000, metadata = 30000, vbtable = 40000;
        when(reader.readU64(person)).thenReturn(vtable);
        when(reader.readU64(vtable - 8)).thenReturn(metadata);
        for (int displacement : List.of(layout.playerDisplacement(), layout.playerStaffDisplacement())) {
            when(reader.readI32(metadata + layout.rttiDisplacementRel())).thenReturn(displacement);
            when(reader.readU64(person - displacement + layout.playerVbtableRel())).thenReturn(vbtable);
            when(reader.readI32(vbtable + layout.playerVbtablePersonRel()))
                    .thenReturn(displacement - layout.playerVbtableRel());
            assertThat(WindowsScoutingWriter.playerComplete(reader, person, layout)).isEqualTo(person - displacement);
        }
        when(reader.readI32(vbtable + layout.playerVbtablePersonRel())).thenReturn(123);
        assertThatThrownBy(() -> WindowsScoutingWriter.playerComplete(reader, person, layout))
                .isInstanceOf(IOException.class).hasMessageContaining("player interface");
    }
}
