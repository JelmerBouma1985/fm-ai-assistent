package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.NativeModuleProfile;
import com.github.fmaiassistent.memory.ProcessMemoryReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic failure injection exercises orchestration, not native Game Pass or Steam offsets. */
class OptionalPeopleCaptureTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"stable", "bounds", "last-slot", "late-bounds"})
    void registryVerificationIsBoundedAndRejectsReplacedCoreTables(String change) throws Exception {
        var reader = mock(ProcessMemoryReader.class);
        byte[] pointers = new byte[131080];
        byte[] current = pointers.clone();
        if (change.equals("last-slot")) current[current.length - 1] = 1;
        var bounds = new com.github.fmaiassistent.linux.FmOffsets.Bounds(0x10000L, 0x10000L + pointers.length);
        doAnswer(call -> {
            long address = call.getArgument(0); byte[] destination = call.getArgument(1);
            int offset = call.getArgument(2), length = call.getArgument(3);
            assertThat(length).isLessThanOrEqualTo(65536);
            System.arraycopy(current, Math.toIntExact(address - bounds.start()), destination, offset, length);
            return null;
        }).when(reader).readInto(anyLong(), any(byte[].class), anyInt(), anyInt());
        try (var offsets = mockStatic(com.github.fmaiassistent.linux.FmOffsets.class)) {
            offsets.when(() -> com.github.fmaiassistent.linux.FmOffsets.peopleBounds(reader, 1, BASE))
                    .thenReturn(change.equals("bounds") ? new com.github.fmaiassistent.linux.FmOffsets.Bounds(0x20000L, 0x20008L) : bounds);
            if (change.equals("late-bounds")) offsets.when(() -> com.github.fmaiassistent.linux.FmOffsets.peopleBounds(reader, 1, BASE))
                    .thenReturn(bounds, new com.github.fmaiassistent.linux.FmOffsets.Bounds(0x20000L, 0x20008L));
            if (change.equals("stable")) assertThatCode(() -> PeopleExporter.verifyPeopleTable(reader, 1, BASE, bounds, pointers)).doesNotThrowAnyException();
            else assertThatThrownBy(() -> PeopleExporter.verifyPeopleTable(reader, 1, BASE, bounds, pointers))
                    .isInstanceOf(PlayerSnapshotValidator.PersonIdentityChangedException.class);
        }
    }

    private static final long BASE = 0x10000000L;
    private static final long PLAYER = 0x20000000L;
    private static final long STAFF = 0x30000000L;
    private static final String PROFILE = NativeModuleProfile.STEAM_26620374_SHA256;

    enum Stage {
        CONTRACTS("Detailed contracts and future transfers", List.of("_contracts", "_future_contracts", "_award_names",
                "_contract_competition_names", "transfer_agreed", "future_transfer_club", "future_transfer_date", "future_transfer_contract_end_date")),
        AWARDS("Award names", List.of("_award_names")),
        COMPETITIONS("Contract competition names", List.of("_contract_competition_names")),
        MANAGEMENT("Management and staff profiles", List.of("non_playing_profile_hex", "non_playing_profile_sha256"));
        final String label;
        final List<String> fields;
        Stage(String label, List<String> fields) { this.label = label; this.fields = fields; }
    }

    static Stream<Arguments> recoverableFailures() {
        return Stream.of(Stage.values()).flatMap(stage -> Stream.of(Arguments.of(stage, false), Arguments.of(stage, true)));
    }

    @ParameterizedTest
    @MethodSource("recoverableFailures")
    void failedStageClearsPartialAndStaleValuesAcrossPlayerAndStaffRowsAndContinues(Stage failed, boolean runtime) throws Exception {
        Fixture fixture = new Fixture();
        Exception error = runtime ? new IllegalStateException("secret /private/path") : new IOException("secret /private/path");
        try (Captures captures = new Captures(fixture)) {
            captures.fail(failed, error, false);
            var warnings = PeopleExporter.captureOptionalData(fixture.reader, BASE, fixture.rows);
            assertThat(warnings).containsExactly(failed.label + " unavailable; affected fields are unknown.");
            assertThat(warnings.toString()).doesNotContain("secret", "/private/path");
            for (var row : fixture.rows) {
                for (String field : failed.fields) assertThat(row).containsEntry(field, null);
                assertThat(row).containsEntry("condition", 8900).containsEntry("sharpness", 7300)
                        .containsEntry("fatigue", -48).containsEntry("salary_weekly_raw", 1234)
                        .containsEntry("player_traits", "Runs With Ball Often");
                if (failed == Stage.CONTRACTS) {
                    assertThat(row).containsEntry("_future_transfer_profile", "UNAVAILABLE")
                            .containsEntry("_contract_profile", PROFILE)
                            .containsEntry("non_playing_profile_hex", "profile-data");
                } else {
                    assertThat(row).containsEntry("_contracts", List.of("contract"))
                            .containsEntry("_future_contracts", List.of("future"));
                }
            }
            if (failed == Stage.CONTRACTS) {
                captures.awards.verifyNoInteractions();
                captures.competitions.verifyNoInteractions();
                captures.management.verify(() -> NonPlayingProfileExporter.capture(fixture.reader, BASE, fixture.rows));
            } else if (failed == Stage.AWARDS) {
                captures.competitions.verify(() -> ContractCompetitionNameExporter.capture(fixture.reader, BASE, fixture.rows));
                captures.management.verify(() -> NonPlayingProfileExporter.capture(fixture.reader, BASE, fixture.rows));
                assertThat(fixture.rows).allSatisfy(row -> assertThat(row).containsEntry("_contract_competition_names", List.of("competition")));
            } else if (failed == Stage.COMPETITIONS) {
                captures.management.verify(() -> NonPlayingProfileExporter.capture(fixture.reader, BASE, fixture.rows));
                assertThat(fixture.rows).allSatisfy(row -> assertThat(row).containsEntry("_award_names", List.of("award")));
            }
        }
    }

    @Test void independentFailuresAppendWarningsWithoutDiscardingOtherSuccessfulStages() throws Exception {
        Fixture fixture = new Fixture();
        try (Captures captures = new Captures(fixture)) {
            captures.fail(Stage.AWARDS, new IOException("Award unavailable"), false);
            captures.fail(Stage.MANAGEMENT, new IllegalStateException("Profile unavailable"), false);
            var warnings = PeopleExporter.captureOptionalData(fixture.reader, BASE, fixture.rows);
            assertThat(warnings).containsExactly(
                    "Award names unavailable; affected fields are unknown.",
                    "Management and staff profiles unavailable; affected fields are unknown.");
            assertThat(fixture.rows).allSatisfy(row -> assertThat(row).containsEntry("_award_names", null)
                    .containsEntry("non_playing_profile_hex", null).containsEntry("non_playing_profile_sha256", null)
                    .containsEntry("_contracts", List.of("contract"))
                    .containsEntry("_contract_competition_names", List.of("competition")));
            assertThatThrownBy(() -> warnings.add("mutable")).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test void successfulOptionalStagesKeepCapturedValuesAndEmitNoWarnings() throws Exception {
        Fixture fixture = new Fixture();
        try (Captures ignored = new Captures(fixture)) {
            assertThat(PeopleExporter.captureOptionalData(fixture.reader, BASE, fixture.rows)).isEmpty();
            assertThat(fixture.rows).allSatisfy(row -> assertThat(row)
                    .containsEntry("_contracts", List.of("contract")).containsEntry("_future_contracts", List.of("future"))
                    .containsEntry("_award_names", List.of("award")).containsEntry("_contract_competition_names", List.of("competition"))
                    .containsEntry("non_playing_profile_hex", "profile-data").containsEntry("non_playing_profile_sha256", PROFILE)
                    .containsEntry("transfer_agreed", true).containsEntry("future_transfer_club", "Future club"));
        }
    }

    @Test void changedOwnerAfterOptionalFailureStillAbortsTheSnapshot() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.reader.readBytes(PLAYER, 16)).thenReturn(prefix(0, 11), prefix(0, 99));
        try (Captures captures = new Captures(fixture)) {
            captures.fail(Stage.AWARDS, new IOException("Award unavailable"), false);
            assertThatThrownBy(() -> PeopleExporter.captureOptionalData(fixture.reader, BASE, fixture.rows))
                    .isInstanceOf(PlayerSnapshotValidator.PersonIdentityChangedException.class)
                    .hasMessageContaining("changed during extraction");
        }
    }

    @Test void invalidOwnerBeforeOptionalCaptureNeverRunsAnyOptionalStage() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.reader.readBytes(STAFF, 16)).thenReturn(prefix(9, 22));
        try (Captures captures = new Captures(fixture)) {
            assertThatThrownBy(() -> PeopleExporter.captureOptionalData(fixture.reader, BASE, fixture.rows))
                    .isInstanceOf(PlayerSnapshotValidator.PersonIdentityChangedException.class);
            captures.contracts.verifyNoInteractions();
            captures.management.verifyNoInteractions();
        }
    }

    enum FatalFailure { IDENTITY, CANCELLATION, INTERRUPTION }

    @ParameterizedTest
    @EnumSource(FatalFailure.class)
    void identityExceptionsAndCancellationRemainFatal(FatalFailure fatal) throws Exception {
        Fixture fixture = new Fixture();
        Exception error = switch (fatal) {
            case IDENTITY -> new PlayerSnapshotValidator.PersonIdentityChangedException("PERSON changed");
            case CANCELLATION -> new CancellationException("Stopped");
            case INTERRUPTION -> new IOException("Interrupted");
        };
        try (Captures captures = new Captures(fixture)) {
            captures.fail(Stage.AWARDS, error, fatal == FatalFailure.INTERRUPTION);
            assertThatThrownBy(() -> PeopleExporter.captureOptionalData(fixture.reader, BASE, fixture.rows)).isSameAs(error);
            captures.competitions.verifyNoInteractions();
            captures.management.verifyNoInteractions();
        } finally { Thread.interrupted(); }
    }

    private static byte[] prefix(int index, int uid) {
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(0x140000000L).putInt(index).putInt(uid).array();
    }

    private static final class Fixture {
        final ProcessMemoryReader reader = mock(ProcessMemoryReader.class);
        final List<Map<String, Object>> rows;
        Fixture() throws IOException {
            when(reader.platform()).thenReturn(ProcessMemoryReader.Platform.WINDOWS);
            when(reader.readBytes(PLAYER, 16)).thenReturn(prefix(0, 11));
            when(reader.readBytes(STAFF, 16)).thenReturn(prefix(1, 22));
            var player = owner(); player.put("record", "0x20000000"); player.put("index", 0); player.put("unique_id", 11L);
            var staff = owner(); staff.put("record_address", "0x30000000"); staff.put("staff_index", 1); staff.put("unique_id", 22L);
            rows = List.of(player, staff);
        }
        private static Map<String, Object> owner() {
            var row = new HashMap<String, Object>(Map.of("condition", 8900, "sharpness", 7300, "fatigue", -48,
                    "salary_weekly_raw", 1234, "player_traits", "Runs With Ball Often"));
            for (Stage stage : Stage.values()) for (String field : stage.fields) row.put(field, "Stale value");
            return row;
        }
    }

    private static final class Captures implements AutoCloseable {
        final MockedStatic<ContractExporter> contracts = mockStatic(ContractExporter.class);
        final MockedStatic<AwardNameExporter> awards = mockStatic(AwardNameExporter.class);
        final MockedStatic<ContractCompetitionNameExporter> competitions = mockStatic(ContractCompetitionNameExporter.class);
        final MockedStatic<NonPlayingProfileExporter> management = mockStatic(NonPlayingProfileExporter.class);
        final Fixture fixture;
        Captures(Fixture fixture) {
            this.fixture = fixture;
            contracts.when(() -> ContractExporter.capture(fixture.reader, BASE, fixture.rows)).thenAnswer(invocation -> {
                fixture.rows.forEach(row -> {
                    row.put("_contract_profile", PROFILE); row.put("_contracts", List.of("contract"));
                    row.put("_future_contracts", List.of("future")); row.put("_future_transfer_profile", "AVAILABLE");
                    row.put("transfer_agreed", true); row.put("future_transfer_club", "Future club");
                    row.put("future_transfer_date", "2027-01-01"); row.put("future_transfer_contract_end_date", "2030-01-01");
                }); return null;
            });
            awards.when(() -> AwardNameExporter.capture(fixture.reader, BASE, fixture.rows)).thenAnswer(invocation -> {
                fixture.rows.forEach(row -> row.put("_award_names", List.of("award"))); return null;
            });
            competitions.when(() -> ContractCompetitionNameExporter.capture(fixture.reader, BASE, fixture.rows)).thenAnswer(invocation -> {
                fixture.rows.forEach(row -> row.put("_contract_competition_names", List.of("competition"))); return null;
            });
            management.when(() -> NonPlayingProfileExporter.capture(fixture.reader, BASE, fixture.rows)).thenAnswer(invocation -> {
                fixture.rows.stream().filter(row -> NativeModuleProfile.supported(String.valueOf(row.get("_contract_profile")))).forEach(row -> {
                    row.put("non_playing_profile_hex", "profile-data"); row.put("non_playing_profile_sha256", PROFILE);
                }); return null;
            });
        }
        void fail(Stage stage, Exception error, boolean interrupt) {
            org.mockito.stubbing.Answer<Void> failure = invocation -> {
                // A verified capture can fail after publishing some fields; every stale/partial value must be discarded.
                if (stage == Stage.CONTRACTS) fixture.rows.forEach(row -> row.put("_contract_profile", PROFILE));
                for (String field : stage.fields) fixture.rows.getFirst().put(field, "Partial value");
                if (interrupt) Thread.currentThread().interrupt();
                throw error;
            };
            switch (stage) {
                case CONTRACTS -> contracts.when(() -> ContractExporter.capture(fixture.reader, BASE, fixture.rows)).thenAnswer(failure);
                case AWARDS -> awards.when(() -> AwardNameExporter.capture(fixture.reader, BASE, fixture.rows)).thenAnswer(failure);
                case COMPETITIONS -> competitions.when(() -> ContractCompetitionNameExporter.capture(fixture.reader, BASE, fixture.rows)).thenAnswer(failure);
                case MANAGEMENT -> management.when(() -> NonPlayingProfileExporter.capture(fixture.reader, BASE, fixture.rows)).thenAnswer(failure);
            }
        }
        @Override public void close() { management.close(); competitions.close(); awards.close(); contracts.close(); }
    }
}
