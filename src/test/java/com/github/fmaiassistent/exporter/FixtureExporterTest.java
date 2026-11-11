package com.github.fmaiassistent.exporter;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixtureExporterTest {
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
