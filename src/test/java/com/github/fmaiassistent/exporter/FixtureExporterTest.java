package com.github.fmaiassistent.exporter;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixtureExporterTest {
    @Test
    void unreadableScanRegionDoesNotReturnASuccessfulPartialFixtureResult() throws Exception {
        var memory = org.mockito.Mockito.mock(com.github.fmaiassistent.memory.ProcessMemoryReader.class);
        var manager = new com.github.fmaiassistent.managedclub.ManagedClubIdentity(1, 42L, "Manager", 0x20000L, 0x30000L, "Club");
        org.mockito.Mockito.when(memory.readU64(0x10000L)).thenReturn(0x20000L);
        org.mockito.Mockito.when(memory.readU64(0x20030L)).thenReturn(0x30000L);
        org.mockito.Mockito.when(memory.readU16(0x200a8L)).thenReturn(7000);
        org.mockito.Mockito.when(memory.maps()).thenReturn(java.util.List.of(
                new com.github.fmaiassistent.memory.MemoryRegion(0x40000L, 0x40080L, "rw-", 0, "", "", "")));
        org.mockito.Mockito.when(memory.readBytes(0x40000L, 128)).thenThrow(new java.io.IOException("Unreadable region"));
        try (var offsets = org.mockito.Mockito.mockStatic(com.github.fmaiassistent.linux.FmOffsets.class);
             var strings = org.mockito.Mockito.mockStatic(com.github.fmaiassistent.linux.FmMemoryStrings.class)) {
            offsets.when(() -> com.github.fmaiassistent.linux.FmOffsets.tableBounds(memory, 0, null, "TeamOffset"))
                    .thenReturn(new com.github.fmaiassistent.linux.FmOffsets.Bounds(0x10000L, 0x10008L));
            strings.when(() -> com.github.fmaiassistent.linux.FmMemoryStrings.clubDisplayName(memory, 0x30000L))
                    .thenReturn(java.util.Optional.of("Club"));
            var exporter = new FixtureExporter(org.mockito.Mockito.mock(com.github.fmaiassistent.managedclub.ManagedClubMemoryReader.class));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> exporter.export(memory, 0, null, manager, null))
                    .isInstanceOf(java.io.IOException.class).hasMessageContaining("fixture memory region");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void managerReadFailureReturnsEmptyFixturesWithWarning(boolean ioFailure) throws Exception {
        var memory = org.mockito.Mockito.mock(com.github.fmaiassistent.memory.ProcessMemoryReader.class);
        var managerReader = org.mockito.Mockito.mock(com.github.fmaiassistent.managedclub.ManagedClubMemoryReader.class);
        Exception failure = ioFailure ? new java.io.IOException("Manager unreadable")
                : new IllegalStateException("Manager unreadable");
        org.mockito.Mockito.when(managerReader.read(memory, 0, null)).thenThrow(failure);

        var result = new FixtureExporter(managerReader).exportManagedClubFixtures(memory, 0, null);

        assertEquals(java.util.List.of(), result.rows());
        assertEquals("Manager unreadable", result.managedClubFailure());
        org.assertj.core.api.Assertions.assertThat(result.fixtureFailure()).contains("Managed-club detection failed");
    }

    @Test
    void nationalOnlyManagerSkipsClubFixtureScan() throws Exception {
        var memory = org.mockito.Mockito.mock(com.github.fmaiassistent.memory.ProcessMemoryReader.class);
        var exporter = new FixtureExporter(org.mockito.Mockito.mock(com.github.fmaiassistent.managedclub.ManagedClubMemoryReader.class));
        var nation = new com.github.fmaiassistent.managedclub.ManagedNationalTeam(1, 2, 3, 784, "Netherlands");
        var manager = new com.github.fmaiassistent.managedclub.ManagedClubIdentity(0x1000, 42L, "Manager", 0, 0, "", nation);
        assertEquals(java.util.List.of(), exporter.export(memory, 0, null, manager, null).rows());
        org.mockito.Mockito.verifyNoInteractions(memory);
    }
    @Test
    void unemployedManagerExportsNoFixturesWithoutScanningMemory() throws Exception {
        var memory = org.mockito.Mockito.mock(com.github.fmaiassistent.memory.ProcessMemoryReader.class);
        var exporter = new FixtureExporter(org.mockito.Mockito.mock(com.github.fmaiassistent.managedclub.ManagedClubMemoryReader.class));
        var manager = new com.github.fmaiassistent.managedclub.ManagedClubIdentity(0x1000, 42L, "Manager", 0, 0, "");

        var result = exporter.export(memory, 0, null, manager, java.time.LocalDate.of(2026, 7, 1));

        assertEquals(java.util.List.of(), result.rows());
        assertEquals("2026-07-01", result.gameDate());
        assertEquals(null, result.fixtureFailure());
        org.mockito.Mockito.verifyNoInteractions(memory);
    }

    @Test
    void decodesKickoffTimesFromPackedMatchDate() {
        assertEquals(LocalTime.of(14, 30), FixtureExporter.kickoffTime(0x46ed));
        assertEquals(LocalTime.of(15, 30), FixtureExporter.kickoffTime(0x4ec9));
        assertEquals(LocalTime.of(20, 0), FixtureExporter.kickoffTime(0x72cc));
        assertEquals(LocalTime.of(20, 45), FixtureExporter.kickoffTime(0x78dd));
    }
}
