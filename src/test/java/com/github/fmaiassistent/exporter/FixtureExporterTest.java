package com.github.fmaiassistent.exporter;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixtureExporterTest {

    @Test
    void decodesKickoffTimesFromPackedMatchDate() {
        assertEquals(LocalTime.of(14, 30), FixtureExporter.kickoffTime(0x46ed));
        assertEquals(LocalTime.of(15, 30), FixtureExporter.kickoffTime(0x4ec9));
        assertEquals(LocalTime.of(20, 0), FixtureExporter.kickoffTime(0x72cc));
        assertEquals(LocalTime.of(20, 45), FixtureExporter.kickoffTime(0x78dd));
    }
}
