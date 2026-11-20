package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class PlayerFitnessExporterTest {
    @Test void replaysCurrentBuildCalculatedFitnessFromPassiveRamCapture() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        byte[] captured;
        try (var stream = getClass().getResourceAsStream("/fm27/player-fitness-calculated-26620374-captured.json")) {
            assertThat(stream).isNotNull();
            captured = stream.readNBytes(65_537);
        }
        assertThat(captured.length).isLessThanOrEqualTo(65_536);
        var fixture = mapper.readTree(captured);
        var profile = com.github.fmaiassistent.memory.NativeModuleProfile.STEAM_26620374_SHA256;
        assertThat(fixture.path("moduleSha256").asString()).isEqualTo(profile);
        var bytes = new java.util.HashMap<Long,Byte>();
        fixture.path("memory").properties().forEach(entry -> {
            long address = Long.parseLong(entry.getKey().split(":")[0]);
            byte[] data = java.util.HexFormat.of().parseHex(entry.getValue().asString());
            for (int i = 0; i < data.length; i++) bytes.put(address+i,data[i]);
        });
        ProcessMemoryReader reader = new ProcessMemoryReader() {
            public int pid() { return 8184; }
            public Platform platform() { return Platform.WINDOWS; }
            public List<MemoryRegion> maps() { return List.of(); }
            public void close() { }
            public byte[] readBytes(long address,int size) throws IOException {
                byte[] result = new byte[size];
                for (int i = 0; i < size; i++) {
                    Byte value = bytes.get(address+i);
                    if (value == null) throw new IOException("Uncaptured fitness byte " + Long.toHexString(address+i));
                    result[i] = value;
                }
                return result;
            }
        };
        var rows = new java.util.ArrayList<Map<String,Object>>();
        for (var input : fixture.path("input")) {
            var row = new java.util.HashMap<String,Object>();
            input.properties().forEach(entry -> row.put(entry.getKey(), entry.getValue().isNumber()
                    ? entry.getValue().asInt() : entry.getValue().asString()));
            rows.add(row);
        }
        var method = PlayerFitnessExporter.class.getDeclaredMethod("captureRows", ProcessMemoryReader.class,
                long.class, List.class, java.time.LocalDate.class, String.class);
        method.setAccessible(true);
        method.invoke(null, reader, fixture.path("module").asLong(), rows,
                java.time.LocalDate.parse(fixture.path("date").asString()), profile);
        tools.jackson.databind.JsonNode actual = mapper.valueToTree(rows);
        assertThat(actual).isEqualTo(fixture.path("expected"));
    }

    @Test void currentBuildLayoutsAreAnchoredAndRejectEveryShiftedMember() throws Exception {
        String profile = com.github.fmaiassistent.memory.NativeModuleProfile.STEAM_26620374_SHA256;
        var layouts = List.of(
                com.github.fmaiassistent.memory.PlayerMatchLoadLayout.forModule(profile),
                com.github.fmaiassistent.memory.PlayerMedicalAssessmentLayout.forModule(profile),
                com.github.fmaiassistent.memory.PlayerMedicalStaffLayout.forModule(profile),
                com.github.fmaiassistent.memory.PlayerTrainingLoadLayout.forModule(profile),
                com.github.fmaiassistent.memory.PlayerTrainingSelectionLayout.forModule(profile),
                com.github.fmaiassistent.memory.PlayerInjuryRiskLayout.TrainingCategories.forModule(profile),
                com.github.fmaiassistent.memory.PlayerInjuryRiskLayout.Calculation.forModule(profile));
        var validators = List.of("validatePlayerMatchLoadLayout", "validatePlayerMedicalAssessmentLayout",
                "validatePlayerMedicalStaffLayout", "validatePlayerTrainingLoadLayout",
                "validatePlayerTrainingSelectionLayout", "validatePlayerInjuryTrainingLayout",
                "validatePlayerInjuryCalculationLayout");
        for (int i = 0; i < layouts.size(); i++) {
            Object layout = layouts.get(i);
            var validator = PlayerSnapshotValidator.class.getMethod(validators.get(i), layout.getClass());
            validator.invoke(null, layout);
            rejectShiftedMembers(layout, shifted -> validator.invoke(null, shifted));
        }
    }

    @FunctionalInterface private interface LayoutValidation { void accept(Object value) throws Exception; }

    private static void rejectShiftedMembers(Object record, LayoutValidation validate)
            throws Exception {
        var components = record.getClass().getRecordComponents();
        var types = java.util.Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
        for (int i = 0; i < components.length; i++) {
            Object value = components[i].getAccessor().invoke(record);
            if (value instanceof Number) {
                Object[] values = new Object[components.length];
                for (int j = 0; j < components.length; j++) values[j] = components[j].getAccessor().invoke(record);
                if (value instanceof Long number) values[i] = Long.valueOf(number + 1);
                else values[i] = Integer.valueOf(((Integer)value) + 1);
                var shifted = record.getClass().getDeclaredConstructor(types).newInstance(values);
                assertThatThrownBy(() -> validate.accept(shifted)).hasCauseInstanceOf(IOException.class);
            } else if (value != null && value.getClass().isRecord()) {
                int changed = i;
                rejectShiftedMembers(value, nested -> {
                    Object[] values = new Object[components.length];
                    for (int j = 0; j < components.length; j++) values[j] = components[j].getAccessor().invoke(record);
                    values[changed] = nested;
                    validate.accept(record.getClass().getDeclaredConstructor(types).newInstance(values));
                });
            }
        }
    }

    @Test void ordinaryRefreshWithAutomaticModuleBaseEntersFitnessCapture() {
        var reader = new Memory();
        var row = Map.<String,Object>of("record","0x10000","index",1,"unique_id",2L);
        assertThatThrownBy(() -> PlayerFitnessExporter.capture(reader,null,List.of(row),null))
                .isInstanceOf(IOException.class).hasMessage("Reached fitness identity read");
        assertThat(reader.mapsRead).isTrue();
        assertThat(reader.identityRead).isTrue();
    }

    @Test void suppliedModuleDoesNotRequireAnotherMappingLookup() throws Exception {
        var reader = new Memory();
        PlayerFitnessExporter.capture(reader,0x10000000L,List.of(),null);
        assertThat(reader.mapsRead).isFalse();
        assertThat(reader.identityRead).isFalse();
    }

    private static class Memory implements ProcessMemoryReader {
        boolean mapsRead,identityRead;
        public Platform platform(){return Platform.WINDOWS;}
        public int pid(){return 1;}
        public List<MemoryRegion> maps(){
            mapsRead=true;
            return List.of(new MemoryRegion(0x10000000L,0x20000000L,"r--",0,"","","game_plugin.dll"));
        }
        public byte[] readBytes(long address,int size)throws IOException {
            identityRead=true;
            assertThat(address).isEqualTo(0x10000L);
            throw new IOException("Reached fitness identity read");
        }
        public void close(){}
    }
}
