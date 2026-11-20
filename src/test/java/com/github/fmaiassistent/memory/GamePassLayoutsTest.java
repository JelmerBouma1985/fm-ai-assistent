package com.github.fmaiassistent.memory;

import com.github.fmaiassistent.exporter.PlayerSnapshotValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Xbox / PC Game Pass image of game_plugin.dll is a separately packed build whose
 * absolute RVAs are not a constant shift of the Steam image. Every root below was
 * resolved on the live Game Pass process by RTTI class name (vtables) or by a unique
 * live value (globals); record-relative offsets are shared with Steam.
 */
class GamePassLayoutsTest {
    private static final String GAME_PASS = NativeModuleProfile.GAME_PASS_SHA256;
    private static final String GAME_PASS_2524 = NativeModuleProfile.GAME_PASS_2524_SHA256;

    @Test
    void gamePassImageIsASupportedProfile() {
        assertThat(NativeModuleProfile.supported(GAME_PASS)).isTrue();
        assertThat(NativeModuleProfile.supportedProfiles())
                .containsExactlyInAnyOrder(NativeModuleProfile.FM27_SHA256, NativeModuleProfile.UPDATED_SHA256,
                        GAME_PASS, NativeModuleProfile.FM27_2701_SHA256, NativeModuleProfile.STEAM_26577676_SHA256,
                        NativeModuleProfile.STEAM_26620374_SHA256, GAME_PASS_2524);
    }

    @Test
    void unverifiedGamePassBuildStaysUnsupported() {
        String unknown = "ae60203e75be891a1911377d9fa6721628b34f6a689e598bc08f995367623f6a";
        assertThat(NativeModuleProfile.supported(unknown)).isFalse();
        assertThatThrownBy(() -> NativeModuleProfile.rva(unknown, 0x56F2EF0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gamePass2524MapsTheUefaRoot() {
        assertThat(NativeModuleProfile.mapsRva(GAME_PASS_2524, 0x56C212A)).isTrue();
        assertThat(NativeModuleProfile.rva(GAME_PASS_2524, 0x56C212A)).isEqualTo(0x53B2EEA);
        assertThat(ClubUefaCoefficientLayout.forModuleIfMapped(GAME_PASS_2524)).isPresent();
    }

    @Test
    void gamePass2524UnmappedRootNamesTheImage() {
        assertThatThrownBy(() -> NativeModuleProfile.rva(GAME_PASS_2524, 0x1234))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Game Pass 27.1.0 image");
    }

    @ParameterizedTest
    @MethodSource("gamePassProfiles")
    void gamePassRecognitionDoesNotEnableUnverifiedSteamFitnessCalculations(String image) {
        assertThat(NativeModuleProfile.supportsFitness(image)).isFalse();
        assertThatThrownBy(() -> PlayerMatchLoadLayout.forModule(image))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not verified");
        assertThat(NativeModuleProfile.supportsFitness(NativeModuleProfile.FM27_SHA256)).isTrue();
        assertThat(NativeModuleProfile.supportsFitness(NativeModuleProfile.STEAM_26620374_SHA256)).isTrue();
    }

    private static final Map<Integer, Integer> GAME_PASS_EXPECTED = Map.ofEntries(
            Map.entry(0x4B00AE8, 0x4AC05F8),  // db::FULL_CONTRACT
            Map.entry(0x4B01058, 0x4AC0B68),  // db::BASIC_CONTRACT
            Map.entry(0x4C8C898, 0x4C3CB08),  // TRANSFER_MANAGER (+0x18 view)
            Map.entry(0x4CB2718, 0x4C62AE8),  // db::NON_PLAYING_DATA member
            Map.entry(0x4CC08D8, 0x4C70268),  // db::HUMAN_NON_PLAYER (+0x440 PERSON view)
            Map.entry(0x4CDA1F8, 0x4C89B68),  // db::CLUB
            Map.entry(0x4CDFAD8, 0x4C8F438),  // db::COMP
            Map.entry(0x4CE1108, 0x4C90A78),  // db::TEAM
            Map.entry(0x4CEC0B8, 0x4C9B0E8),  // db::NATIONAL_TEAM_CONTAINER
            Map.entry(0x4CF07F8, 0x4C9F8A8),  // db::CLUB_FINANCE_BASE
            Map.entry(0x4CF0CC8, 0x4C9FD78),  // db::CLUB_FINANCE
            Map.entry(0x4CF6078, 0x4CA5198),  // db::AWARD
            Map.entry(0x4D8BE78, 0x4D3B3C8),  // db::ACTUAL_NON_PLAYER (+0x100 PERSON view)
            Map.entry(0x4DC2288, 0x4D71998),  // db::NATIONAL_TEAM
            Map.entry(0x4DC91F8, 0x4D78AF8),  // db::LOAN_CONTRACT
            Map.entry(0x4E195B8, 0x4DC39C8),  // LOAN_OFFER
            Map.entry(0x4F392E8, 0x4EE4098),  // FULL_TRANSFER_OFFER
            Map.entry(0x4F53F28, 0x4EFE928),  // db::B_CLUB_CONTRACT
            Map.entry(0x4F65538, 0x4F103F8),  // db::LEAGUE_BODY_CLUB
            Map.entry(0x4F75F38, 0x4F21258),  // db::ACTUAL_PLAYER_AND_NON_PLAYER (+0x380 PERSON view)
            Map.entry(0x56AD378, 0x56257C8),  // current game date
            Map.entry(0x56F2EF0, 0x5672240),  // transfer-manager root
            Map.entry(0x56F32C8, 0x5672618),  // current human manager
            Map.entry(0x57035E8, 0x5683BD8),  // database registry table
            Map.entry(0x57036D0, 0x5683CC0),  // AWARD registry slot
            Map.entry(0x57036E0, 0x5683CD0),  // CLUB registry slot
            Map.entry(0x57036E8, 0x5683CD8),  // COMP registry slot
            Map.entry(0x5703730, 0x5683D20),  // national containers registry slot
            Map.entry(0x5703738, 0x5683D28),  // PEOPLE registry slot
            Map.entry(0x5703768, 0x5683D58)); // TEAM registry slot

    private static final Map<Integer, Integer> GAME_PASS_2524_EXPECTED = Map.ofEntries(
            Map.entry(0x4B00AE8, 0x4897AA8),  // db::FULL_CONTRACT
            Map.entry(0x4B01058, 0x4898018),  // db::BASIC_CONTRACT
            Map.entry(0x4C8C898, 0x4A26E28),  // TRANSFER_MANAGER (+0x18 view)
            Map.entry(0x4CB2718, 0x4A1E5A8),  // db::NON_PLAYING_DATA member
            Map.entry(0x4CC08D8, 0x4A47238),  // db::HUMAN_NON_PLAYER (+0x440 PERSON view)
            Map.entry(0x4CDA1F8, 0x4A5E628),  // db::CLUB
            Map.entry(0x4CDFAD8, 0x4A62638),  // db::COMP
            Map.entry(0x4CE1108, 0x4A63288),  // db::TEAM
            Map.entry(0x4CEC0B8, 0x4A6C0C8),  // db::NATIONAL_TEAM_CONTAINER
            Map.entry(0x4CF07F8, 0x4A702E8),  // db::CLUB_FINANCE_BASE
            Map.entry(0x4CF0CC8, 0x4A707B8),  // db::CLUB_FINANCE
            Map.entry(0x4CF6078, 0x4A75248),  // db::AWARD
            Map.entry(0x4D8BE78, 0x4AF4318),  // db::ACTUAL_NON_PLAYER (+0x100 PERSON view)
            Map.entry(0x4DC2288, 0x4B23B28),  // db::NATIONAL_TEAM
            Map.entry(0x4DC91F8, 0x4B2A708),  // db::LOAN_CONTRACT
            Map.entry(0x4E195B8, 0x4B68EB8),  // LOAN_OFFER
            Map.entry(0x4F392E8, 0x4C6E928),  // FULL_TRANSFER_OFFER
            Map.entry(0x4F53F28, 0x4C88668),  // db::B_CLUB_CONTRACT
            Map.entry(0x4F65538, 0x4C40AE8),  // db::LEAGUE_BODY_CLUB
            Map.entry(0x4F75F38, 0x4C9F1E8),  // db::ACTUAL_PLAYER_AND_NON_PLAYER (+0x380 PERSON view)
            Map.entry(0x56AD378, 0x539DD10),  // current game date
            Map.entry(0x56C212A, 0x53B2EEA),  // UEFA coefficient rule version
            Map.entry(0x56F2EF0, 0x53EAD60),  // transfer-manager root
            Map.entry(0x56F32C8, 0x53EB138),  // current human manager
            Map.entry(0x57035E8, 0x53FACA0),  // database registry table
            Map.entry(0x57036D0, 0x53FAD88),  // registry slot +0xE8
            Map.entry(0x57036E0, 0x53FAD98),  // registry slot +0xF8 (clubs)
            Map.entry(0x57036E8, 0x53FADA0),  // registry slot +0x100 (competitions)
            Map.entry(0x5703730, 0x53FADE8),  // registry slot +0x148
            Map.entry(0x5703738, 0x53FADF0),  // registry slot +0x150 (people)
            Map.entry(0x5703768, 0x53FAE20)); // registry slot +0x180 (teams)

    static Stream<Arguments> gamePassImages() {
        return Stream.of(Arguments.of(GAME_PASS, GAME_PASS_EXPECTED, null),
                Arguments.of(GAME_PASS_2524, GAME_PASS_2524_EXPECTED, NativeModuleProfile.STEAM_26620374_SHA256));
    }

    static Stream<Arguments> gamePassProfiles() {
        return Stream.of(Arguments.of(GAME_PASS), Arguments.of(GAME_PASS_2524));
    }

    @ParameterizedTest
    @MethodSource("gamePassImages")
    void everyLegacyRootMapsToItsRttiVerifiedGamePassRoot(String image, Map<Integer, Integer> expected, String orderReference) {
        expected.forEach((legacy, gamePass) -> assertThat(NativeModuleProfile.rva(image, legacy))
                .as("legacy 0x%x", legacy).isEqualTo(gamePass));
        var ordered = new TreeMap<>(expected);
        if (orderReference == null) {
            // The first image reorders nothing: a mapping that breaks the legacy root order names the wrong class.
            Integer previous = null;
            for (int gamePass : ordered.values()) {
                if (previous != null) assertThat(gamePass).as("root order after 0x%x", previous).isGreaterThan(previous);
                previous = gamePass;
            }
        } else {
            // 27.1.0 reorders two class pairs; the order must match the other 27.1.0 image's, root for root.
            var byRank = ordered.keySet().stream().sorted(Comparator.comparingInt(k -> expected.get(k))).toList();
            var referenceRank = ordered.keySet().stream()
                    .sorted(Comparator.comparingInt(k -> NativeModuleProfile.rva(orderReference, k))).toList();
            assertThat(byRank).isEqualTo(referenceRank);
        }
    }

    @ParameterizedTest
    @MethodSource("gamePassProfiles")
    void independentlyResolvedRootsKeepTheirSteamDistances(String image) {
        // Registry slots were found from live counts, the table by its score, the two manager
        // roots by live pointers; their spacing must still match the Steam image.
        int table = 0x57035E8;
        for (int slot : new int[]{0x57036D0, 0x57036E0, 0x57036E8, 0x5703730, 0x5703738, 0x5703768}) {
            assertThat(NativeModuleProfile.rva(image, slot) - NativeModuleProfile.rva(image, table))
                    .as("slot 0x%x", slot).isEqualTo(slot - table);
        }
        assertThat(NativeModuleProfile.rva(image, 0x56F32C8) - NativeModuleProfile.rva(image, 0x56F2EF0))
                .isEqualTo(0x56F32C8 - 0x56F2EF0);
    }

    @Test
    void unresolvedRootsStayUnavailableInsteadOfGuessed() {
        assertThat(NativeModuleProfile.mapsRva(GAME_PASS, 0x56C212A)).isFalse();
        assertThatThrownBy(() -> NativeModuleProfile.rva(GAME_PASS, 0x56C212A))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClubUefaCoefficientLayout.forModule(GAME_PASS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ClubUefaCoefficientLayout.forModuleIfMapped(GAME_PASS)).isEmpty();
        assertThat(ClubUefaCoefficientLayout.forModuleIfMapped(NativeModuleProfile.UPDATED_SHA256)).isPresent();
    }

    @ParameterizedTest
    @MethodSource("gamePassProfiles")
    void gamePassLayoutsPassValidationAndRejectEveryShiftedDisplacement(String image) throws Exception {
        assertNumericDriftRejected(ClubIdentityLayout.forModule(image), PlayerSnapshotValidator::validateClubIdentityLayout);
        assertNumericDriftRejected(ClubFinanceLayout.forModule(image),
                layout -> PlayerSnapshotValidator.validateClubFinanceLayout(ClubRecordLayout.current(), layout));
        assertNumericDriftRejected(ClubFinancialLedgerLayout.forModule(image), PlayerSnapshotValidator::validateClubFinancialLedgerLayout);
        assertNumericDriftRejected(ClubFinancialLoanLayout.forModule(image), PlayerSnapshotValidator::validateClubFinancialLoanLayout);
        assertNumericDriftRejected(ClubFinancialCompetitionLayout.forModule(image), PlayerSnapshotValidator::validateClubFinancialCompetitionLayout);
        assertNumericDriftRejected(AwardNameLayout.forModule(image), PlayerSnapshotValidator::validateAwardNameLayout);
        assertNumericDriftRejected(FutureEmploymentLayout.forModule(image), PlayerSnapshotValidator::validateFutureEmploymentLayout);
        assertNumericDriftRejected(FutureTransferLayout.forModule(image), PlayerSnapshotValidator::validateFutureTransferLayout);
        assertNumericDriftRejected(NonPlayingProfileLayout.forModule(image), PlayerSnapshotValidator::validateNonPlayingProfileLayout);
        assertThatCode(() -> PlayerSnapshotValidator.validateContractLayout(ContractRecordLayout.forModule(image)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("gamePassProfiles")
    void persistedGamePassPayloadsDecodeLikeSteamPayloads(String image) {
        int clock = (2026 << 16) | 335;
        String ledger = "00".repeat(ClubFinancialLedgerLayout.current().payloadBytes());
        assertThat(ClubFinancialLedgerLayout.values(ledger, clock, image))
                .isEqualTo(ClubFinancialLedgerLayout.values(ledger, clock, NativeModuleProfile.FM27_SHA256));
        assertThat(NonPlayingProfileLayout.values("00".repeat(52), image))
                .isEqualTo(NonPlayingProfileLayout.values("00".repeat(52), NativeModuleProfile.FM27_SHA256));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Record> void assertNumericDriftRejected(T known, LayoutCheck<T> check) throws Exception {
        assertThatCode(() -> check.validate(known)).doesNotThrowAnyException();
        RecordComponent[] components = known.getClass().getRecordComponents();
        Class<?>[] types = java.util.Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
        for (int changed = 0; changed < components.length; changed++) {
            if (components[changed].getType() != int.class && components[changed].getType() != long.class) continue;
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) values[i] = components[i].getAccessor().invoke(known);
            if (components[changed].getType() == int.class) values[changed] = (Integer) values[changed] + 8;
            else values[changed] = (Long) values[changed] + 8;
            T shifted = (T) known.getClass().getDeclaredConstructor(types).newInstance(values);
            assertThatThrownBy(() -> check.validate(shifted)).as(known.getClass().getSimpleName() + "." + components[changed].getName())
                    .isInstanceOf(IOException.class);
        }
    }

    @FunctionalInterface
    private interface LayoutCheck<T> { void validate(T layout) throws IOException; }
}
