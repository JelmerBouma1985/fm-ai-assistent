package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.MemoryRegion;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlayerFitnessExporterTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"match_load", "training_load", "injury_susceptibility", "overall_risk", "player context"})
    void aFailedPlayerCalculationPreservesOtherFieldsAndLaterPlayers(String failed) throws Exception {
        var reader = mock(ProcessMemoryReader.class);
        when(reader.readU64(0x10000L)).thenReturn(0x20000L);
        when(reader.readU64(0x11000L)).thenReturn(0x20000L);
        when(reader.readU64(0x20000L - 8)).thenReturn(0x30000L);
        when(reader.readI32(0x30000L + 4)).thenReturn(0x288);
        if (failed.equals("player context")) when(reader.readU64(0x10000L)).thenThrow(new IOException("Subtype unreadable"));
        var first = new java.util.HashMap<String, Object>(Map.of("record", "0x10000", "condition", 8900, "sharpness", 7300, "fatigue", -48));
        var second = new java.util.HashMap<String, Object>(first); second.put("record", "0x11000");
        try (var match = mockConstruction(PlayerMatchLoadReader.class, (mock, context) ->
                when(mock.read(any(), anyLong(), anyLong(), any())).thenAnswer(call -> {
                    failFirstCalculation(failed, "match_load", call.getArgument(1));
                    return new PlayerMatchLoadReader.Result(0, "Light", 0, 0, 0, 14);
                }));
             var training = mockConstruction(PlayerTrainingLoadReader.class, (mock, context) -> {
                when(mock.read(any(), anyLong(), anyLong(), any())).thenAnswer(call -> {
                    failFirstCalculation(failed, "training_load", call.getArgument(1));
                    return new PlayerTrainingLoadReader.Result(1, "Medium", 100, 100, 1, 0, 0, 0);
                });
                when(mock.teamRiskMultiplier(any(), anyLong(), anyLong())).thenReturn(1f);
             });
             var risk = mockConstruction(PlayerInjuryRiskReader.class, (mock, context) -> {
                when(mock.readSusceptibility(any(), anyLong(), anyLong(), any(), anyInt(), anyInt(), any())).thenAnswer(call -> {
                    failFirstCalculation(failed, "injury_susceptibility", call.getArgument(1));
                    return new PlayerInjuryRiskReader.SusceptibilityResult(0, "Low", null);
                });
                when(mock.readRisk(any(), anyLong(), anyLong(), any(), anyInt(), anyInt(), anyInt(), anyInt(), any(), any(), any()))
                        .thenAnswer(call -> {
                            failFirstCalculation(failed, "overall_risk", call.getArgument(1));
                            return new PlayerInjuryRiskReader.Result(0, "Low");
                        });
             })) {
            var method = PlayerFitnessExporter.class.getDeclaredMethod("captureRows", ProcessMemoryReader.class,
                    long.class, List.class, java.time.LocalDate.class, String.class);
            method.setAccessible(true);
            method.invoke(null, reader, 0x10000000L, List.of(first, second), java.time.LocalDate.of(2026, 8, 6),
                    com.github.fmaiassistent.memory.NativeModuleProfile.STEAM_26620374_SHA256);
            var expected = Map.of("match_load", "Light", "training_load", "Medium", "injury_susceptibility", "Low", "overall_risk", "Low");
            for (var field : expected.keySet()) {
                assertThat(second).containsEntry(field, expected.get(field));
                boolean unavailable = failed.equals(field) || failed.equals("player context")
                        || (failed.equals("match_load") && field.equals("overall_risk"));
                assertThat(first).containsEntry(field, unavailable ? null : expected.get(field));
            }
        }
    }

    private static void failFirstCalculation(String failed, String field, long player) throws IOException {
        if (player == 0x10000L - 0x288 && failed.equals(field)) throw new IOException("Invalid optional native context");
    }

    enum FitnessFailure { IO, RUNTIME, PROFILE, IDENTITY_CHANGED, DATE_CHANGED, CANCELLED, INTERRUPTED, IDENTITY_EXCEPTION }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("platformFailures")
    void calculationFailuresAreOptionalButSnapshotChangesAndCancellationStillAbort(FitnessFailure failure, ProcessMemoryReader.Platform platform) throws Exception {
        long base = 0x10000000L, person = 0x10000L;
        var reader = mock(ProcessMemoryReader.class);
        when(reader.platform()).thenReturn(platform);
        byte[] prefix = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putLong(0x20000L).putInt(1).putInt(2).array();
        byte[] changed = prefix.clone(); changed[12] = 9;
        when(reader.readBytes(person, 16)).thenReturn(prefix, failure == FitnessFailure.IDENTITY_CHANGED ? changed : prefix);
        var row = new java.util.HashMap<String, Object>(Map.of("record", "0x10000", "index", 1, "unique_id", 2L,
                "condition", 8900, "sharpness", 7300, "fatigue", -48, "player_traits", "Runs With Ball Often"));
        var other = new java.util.HashMap<String, Object>(row);
        other.put("record", "0x11000"); other.put("index", 2); other.put("unique_id", 3L);
        when(reader.readBytes(0x11000L, 16)).thenReturn(java.nio.ByteBuffer.allocate(16)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(0x20000L).putInt(2).putInt(3).array());
        for (var field : List.of("match_load", "training_load", "injury_susceptibility", "overall_risk")) other.put(field, "Old value");
        var rows = List.<Map<String, Object>>of(row, other);
        var date = java.time.LocalDate.of(2026, 7, 6);
        Exception error = switch (failure) {
            case RUNTIME, PROFILE -> new IllegalStateException("Calculation failed");
            case CANCELLED -> new java.util.concurrent.CancellationException("Stopped");
            case IDENTITY_EXCEPTION -> new PlayerSnapshotValidator.PersonIdentityChangedException("PERSON changed");
            default -> new IOException("Calculation memory unavailable");
        };
        // Run the real capture path, failing after match_load was already written.
        try (var offsets = mockStatic(com.github.fmaiassistent.linux.FmOffsets.class);
             var cache = mockConstruction(com.github.fmaiassistent.memory.PagedSnapshotMemoryReader.class, (mock, context) -> {
                 when(mock.platform()).thenReturn(platform);
                 when(mock.readU64(person)).thenReturn(0x20000L);
                 when(mock.readU64(0x11000L)).thenReturn(0x20000L);
                 when(mock.readU64(0x20000L - 8)).thenReturn(0x30000L);
                 when(mock.readI32(0x30000L + 4)).thenReturn(0x288);
             });
             var match = mockConstruction(PlayerMatchLoadReader.class, (mock, context) ->
                     when(mock.read(any(), anyLong(), anyLong(), any())).thenReturn(new PlayerMatchLoadReader.Result(0, "Light", 0, 0, 0, 14)));
             var training = mockConstruction(PlayerTrainingLoadReader.class, (mock, context) ->
                     when(mock.read(any(), anyLong(), anyLong(), any())).thenAnswer(invocation -> {
                         if ((long) invocation.getArgument(1) == 0x11000L - 0x288)
                             return new PlayerTrainingLoadReader.Result(1, "Medium", 100, 100, 1, 0, 0, 0);
                         if (failure == FitnessFailure.INTERRUPTED) Thread.currentThread().interrupt();
                         throw error;
                     }));
             var dates = mockConstruction(com.github.fmaiassistent.linux.GameDateFinder.class, (mock, context) ->
                     when(mock.find(any(), anyLong(), anyInt(), any())).thenReturn(failure == FitnessFailure.DATE_CHANGED
                             ? java.util.Optional.of(date.plusDays(1)) : java.util.Optional.of(date)))) {
            if (failure == FitnessFailure.PROFILE) offsets.when(() -> com.github.fmaiassistent.linux.FmOffsets.moduleProfile(reader, base)).thenThrow(error);
            else offsets.when(() -> com.github.fmaiassistent.linux.FmOffsets.moduleProfile(reader, base))
                    .thenReturn(com.github.fmaiassistent.memory.NativeModuleProfile.STEAM_26620374_SHA256);
            if (failure == FitnessFailure.CANCELLED || failure == FitnessFailure.INTERRUPTED || failure == FitnessFailure.IDENTITY_EXCEPTION) {
                assertThatThrownBy(() -> PlayerFitnessExporter.capture(reader, base, rows, date)).isSameAs(error);
            } else if (failure == FitnessFailure.IDENTITY_CHANGED || failure == FitnessFailure.DATE_CHANGED) {
                assertThatThrownBy(() -> PlayerFitnessExporter.capture(reader, base, rows, date))
                        .isInstanceOf(PlayerSnapshotValidator.PersonIdentityChangedException.class);
            } else {
                assertThatCode(() -> PlayerFitnessExporter.capture(reader, base, rows, date)).doesNotThrowAnyException();
                if (failure == FitnessFailure.PROFILE) {
                    for (var output : rows) for (var field : List.of("match_load", "training_load", "injury_susceptibility", "overall_risk"))
                        assertThat(output).containsEntry(field, null);
                } else {
                    assertThat(row).containsEntry("match_load", "Light").containsEntry("training_load", null);
                    assertThat(other).containsEntry("match_load", "Light").containsEntry("training_load", "Medium");
                    assertThat(other).doesNotContainValue("Old value");
                }
            }
            for (var output : rows) assertThat(output).containsEntry("condition", 8900).containsEntry("sharpness", 7300)
                    .containsEntry("fatigue", -48).containsEntry("player_traits", "Runs With Ball Often");
        } finally { Thread.interrupted(); }
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> platformFailures() {
        return java.util.stream.Stream.of(ProcessMemoryReader.Platform.WINDOWS, ProcessMemoryReader.Platform.LINUX)
                .flatMap(platform -> java.util.Arrays.stream(FitnessFailure.values())
                        .map(failure -> org.junit.jupiter.params.provider.Arguments.of(failure, platform)));
    }

    @Test void missingFitnessModuleLeavesCalculatedFieldsEmptyWithoutFailingLoading() throws Exception {
        var reader = mock(ProcessMemoryReader.class);
        when(reader.platform()).thenReturn(ProcessMemoryReader.Platform.WINDOWS);
        var row = new java.util.HashMap<String, Object>(Map.of("condition", 9000, "match_load", "Old value"));
        try (var offsets = mockStatic(com.github.fmaiassistent.linux.FmOffsets.class)) {
            offsets.when(() -> com.github.fmaiassistent.linux.FmOffsets.findGamePluginBase(reader)).thenThrow(new IOException("No module"));
            assertThatCode(() -> PlayerFitnessExporter.capture(reader, null, List.of(row), null)).doesNotThrowAnyException();
        }
        assertThat(row).containsEntry("condition", 9000).containsEntry("match_load", null).containsEntry("training_load", null)
                .containsEntry("injury_susceptibility", null).containsEntry("overall_risk", null);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            com.github.fmaiassistent.memory.NativeModuleProfile.GAME_PASS_SHA256,
            com.github.fmaiassistent.memory.NativeModuleProfile.GAME_PASS_2524_SHA256})
    void gamePassKeepsAlreadyDecodedScalarsAndTraitsWithoutReadingSteamCalculationRoots(String profile) throws Exception {
        // This checks the unsupported-calculation boundary, not native Game Pass scalar offsets.
        var reader = org.mockito.Mockito.mock(ProcessMemoryReader.class);
        org.mockito.Mockito.when(reader.platform()).thenReturn(ProcessMemoryReader.Platform.WINDOWS);
        byte[] identity = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putLong(0x140000000L).putInt(1).putInt(2).array();
        org.mockito.Mockito.when(reader.readBytes(0x10000L, 16)).thenReturn(identity);
        var row = new java.util.HashMap<String, Object>(Map.of("record", "0x10000", "index", 1, "unique_id", 2L,
                "condition", 8900, "sharpness", 7300, "fatigue", -48, "player_traits", "Runs With Ball Often"));
        row.put("match_load", null);
        row.put("training_load", null);
        row.put("overall_risk", null);
        var before = new java.util.HashMap<>(row);
        try (var offsets = org.mockito.Mockito.mockStatic(com.github.fmaiassistent.linux.FmOffsets.class)) {
            offsets.when(() -> com.github.fmaiassistent.linux.FmOffsets.moduleProfile(reader, 0x10000000L)).thenReturn(profile);
            PlayerFitnessExporter.capture(reader, 0x10000000L, List.of(row), null);
        }
        assertThat(row).isEqualTo(before);
        org.mockito.Mockito.verify(reader).platform();
        org.mockito.Mockito.verify(reader).readBytes(0x10000L, 16);
        org.mockito.Mockito.verifyNoMoreInteractions(reader);
    }

    @Test void replaysCurrentBuildCalculatedFitnessFromPassiveRamCapture(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
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
        // The captured native archive path belongs to a past save. Never consult the developer's current game files.
        String property = PlayerMedicalAssessmentReader.ARCHIVE_PROPERTY;
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, directory.resolve("missing-injury-history.cmt").toString());
            method.invoke(null, reader, fixture.path("module").asLong(), rows,
                    java.time.LocalDate.parse(fixture.path("date").asString()), profile);
        } finally {
            if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
        }
        // This player's susceptibility requires the external injury archive, which is not part of the fixture.
        ((tools.jackson.databind.node.ObjectNode) fixture.path("expected").get(2)).putNull("injury_susceptibility");
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
