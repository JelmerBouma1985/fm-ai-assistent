package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.linux.FmOffsets;
import com.github.fmaiassistent.managedclub.ManagedClubMemoryReader;
import com.github.fmaiassistent.memory.NativeModuleProfile;
import com.github.fmaiassistent.memory.ProcessReaders;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent Python native census is the oracle; no database or game writes. */
@Tag("fm27-live")
@EnabledIfSystemProperty(named="fm27.live.fixtures",matches="true")
class FixtureExporterFm27LiveTest {
    @Test void allCurrentManagedFixturesMatchTheIndependentTypedNativeCensus() throws Exception {
        tools.jackson.databind.JsonNode expected;
        try(var in=Files.newInputStream(Path.of(System.getProperty("fm27.live.fixtures.capture",
                "docs/reverse-engineering/fm27/fixtures_inventory.json")))) {
            byte[] bytes=in.readNBytes(1_048_577);assertThat(bytes.length).isLessThanOrEqualTo(1_048_576);
            expected=JsonMapper.builder().build().readTree(bytes);
        }
        int pid=expected.get("pid").asInt();long base=Long.decode(expected.get("moduleBase").asString());
        assertThat(expected.get("moduleSha256").asString()).isEqualTo(NativeModuleProfile.FM27_SHA256);
        try(var memory=ProcessReaders.open(pid)) { assertThat(FmOffsets.findGamePluginBase(memory)).isEqualTo(base); }
        var actual=new FixtureExporter(new ManagedClubMemoryReader()).exportManagedClubFixtures(pid,FmOffsets.DEFAULT_BUILD,base);
        assertThat(actual.gameDate()).isEqualTo("2026-08-03");
        assertThat(actual.rows()).hasSize(expected.get("rows").size());
        for(var row:expected.get("rows")) {
            long source=row.get("sourceAddress").asLong();
            Map<String,Object> found=actual.rows().stream().filter(r->((Number)r.get("sourceAddress")).longValue()==source).findFirst().orElseThrow();
            for(String field:FixtureExporter.FIELD_NAMES) {
                Object want=field.equals("sourceAddress")?source:field.equals("home")?row.get(field).asBoolean():row.get(field).asString();
                assertThat(found.get(field)).as("%s source=%s",field,Long.toHexString(source)).isEqualTo(want);
            }
        }
    }
}
