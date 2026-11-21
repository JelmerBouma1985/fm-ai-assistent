package com.github.fmaiassistent.exporter;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixtureExporterTest {
    @Test
    void matchBetweenHumanClubsHasOneFixtureFromEachManagersPerspective() throws Exception {
        var memory = org.mockito.Mockito.mock(com.github.fmaiassistent.memory.ProcessMemoryReader.class);
        long first = 0x20000L, second = 0x21000L, homeClub = 0x30000L, awayClub = 0x31000L;
        org.mockito.Mockito.when(memory.readU64(0x10000L)).thenReturn(first);
        org.mockito.Mockito.when(memory.readU64(0x10008L)).thenReturn(second);
        org.mockito.Mockito.when(memory.readU64(first + 0x30)).thenReturn(homeClub);
        org.mockito.Mockito.when(memory.readU64(second + 0x30)).thenReturn(awayClub);
        org.mockito.Mockito.when(memory.readU16(first + 0xa8)).thenReturn(7000);
        org.mockito.Mockito.when(memory.readU16(second + 0xa8)).thenReturn(7000);
        org.mockito.Mockito.when(memory.readU64(0x60018L)).thenReturn(0x61000L);
        var bytes = java.nio.ByteBuffer.allocate(0x48).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bytes.putLong(0, first).putLong(8, second).putLong(0x18, 0x60000L).putLong(0x20, 0x70000L);
        bytes.putShort(0x44, (short) 0x46ed).putShort(0x46, (short) 2026);
        org.mockito.Mockito.when(memory.readBytes(0x40000L, 0x48)).thenReturn(bytes.array());
        org.mockito.Mockito.when(memory.maps()).thenReturn(java.util.List.of(
                new com.github.fmaiassistent.memory.MemoryRegion(0x40000L, 0x40048L, "rw-", 0, "", "", "")));
        try (var offsets = org.mockito.Mockito.mockStatic(com.github.fmaiassistent.linux.FmOffsets.class);
             var strings = org.mockito.Mockito.mockStatic(com.github.fmaiassistent.linux.FmMemoryStrings.class)) {
            offsets.when(() -> com.github.fmaiassistent.linux.FmOffsets.tableBounds(memory, 0, null, "TeamOffset"))
                    .thenReturn(new com.github.fmaiassistent.linux.FmOffsets.Bounds(0x10000L, 0x10010L));
            strings.when(() -> com.github.fmaiassistent.linux.FmMemoryStrings.clubDisplayName(memory, homeClub))
                    .thenReturn(java.util.Optional.of("SC Cambuur"));
            strings.when(() -> com.github.fmaiassistent.linux.FmMemoryStrings.clubDisplayName(memory, awayClub))
                    .thenReturn(java.util.Optional.of("Helmond Sport"));
            strings.when(() -> com.github.fmaiassistent.linux.FmMemoryStrings.objectStringAt(memory, 0x70000L, 0x48))
                    .thenReturn(java.util.Optional.of("Stadium"));
            strings.when(() -> com.github.fmaiassistent.linux.FmMemoryStrings.competitionDisplayName(memory, 0x61000L))
                    .thenReturn(java.util.Optional.of("League"));
            var managers = java.util.List.of(
                    new com.github.fmaiassistent.managedclub.ManagedClubIdentity(1, 41L, "Ties", first, homeClub, "SC Cambuur"),
                    new com.github.fmaiassistent.managedclub.ManagedClubIdentity(2, 42L, "Jemme", second, awayClub, "Helmond Sport"));
            var rows = new FixtureExporter(new com.github.fmaiassistent.managedclub.ManagedClubMemoryReader())
                    .exportAll(memory, 0, null, managers, java.time.LocalDate.of(2026, 8, 1)).rows();
            org.assertj.core.api.Assertions.assertThat(rows).hasSize(2);
            org.assertj.core.api.Assertions.assertThat(rows.stream().filter(row -> Boolean.TRUE.equals(row.get("home"))).findFirst().orElseThrow())
                    .containsEntry("opponent", "Helmond Sport");
            org.assertj.core.api.Assertions.assertThat(rows.stream().filter(row -> Boolean.FALSE.equals(row.get("home"))).findFirst().orElseThrow())
                    .containsEntry("opponent", "SC Cambuur");
        }
    }
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
        org.mockito.Mockito.when(managerReader.readAll(memory, 0, null)).thenThrow(failure);

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
