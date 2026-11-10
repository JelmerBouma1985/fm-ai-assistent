package com.github.fmaiassistent.memory;

import com.github.fmaiassistent.exporter.PlayerSnapshotValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.util.Map;

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

    @Test
    void gamePassImageIsASupportedProfile() {
        assertThat(NativeModuleProfile.supported(GAME_PASS)).isTrue();
        assertThat(NativeModuleProfile.supportedProfiles())
                .containsExactlyInAnyOrder(NativeModuleProfile.FM27_SHA256, NativeModuleProfile.UPDATED_SHA256,
                        GAME_PASS, NativeModuleProfile.FM27_2701_SHA256);
    }

    @Test
    void everyLegacyRootMapsToItsRttiVerifiedGamePassRoot() {
        Map<Integer, Integer> expected = Map.ofEntries(
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
        expected.forEach((legacy, gamePass) -> assertThat(NativeModuleProfile.rva(GAME_PASS, legacy))
                .as("legacy 0x%x", legacy).isEqualTo(gamePass));
        // The images reorder nothing: a mapping that breaks Steam's root order names the wrong class.
        var ordered = new java.util.TreeMap<>(expected);
        Integer previous = null;
        for (int gamePass : ordered.values()) {
            if (previous != null) assertThat(gamePass).as("root order after 0x%x", previous).isGreaterThan(previous);
            previous = gamePass;
        }
    }

    @Test
    void independentlyResolvedRootsKeepTheirSteamDistances() {
        // Registry slots were found from live counts, the table by its score, the two manager
        // roots by live pointers; their spacing must still match the Steam image.
        int table = 0x57035E8;
        for (int slot : new int[]{0x57036D0, 0x57036E0, 0x57036E8, 0x5703730, 0x5703738, 0x5703768}) {
            assertThat(NativeModuleProfile.rva(GAME_PASS, slot) - NativeModuleProfile.rva(GAME_PASS, table))
                    .as("slot 0x%x", slot).isEqualTo(slot - table);
        }
        assertThat(NativeModuleProfile.rva(GAME_PASS, 0x56F32C8) - NativeModuleProfile.rva(GAME_PASS, 0x56F2EF0))
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

    @Test
    void gamePassLayoutsPassValidationAndRejectEveryShiftedDisplacement() throws Exception {
        assertNumericDriftRejected(ClubIdentityLayout.forModule(GAME_PASS), PlayerSnapshotValidator::validateClubIdentityLayout);
        assertNumericDriftRejected(ClubFinanceLayout.forModule(GAME_PASS),
                layout -> PlayerSnapshotValidator.validateClubFinanceLayout(ClubRecordLayout.current(), layout));
        assertNumericDriftRejected(ClubFinancialLedgerLayout.forModule(GAME_PASS), PlayerSnapshotValidator::validateClubFinancialLedgerLayout);
        assertNumericDriftRejected(ClubFinancialLoanLayout.forModule(GAME_PASS), PlayerSnapshotValidator::validateClubFinancialLoanLayout);
        assertNumericDriftRejected(ClubFinancialCompetitionLayout.forModule(GAME_PASS), PlayerSnapshotValidator::validateClubFinancialCompetitionLayout);
        assertNumericDriftRejected(AwardNameLayout.forModule(GAME_PASS), PlayerSnapshotValidator::validateAwardNameLayout);
        assertNumericDriftRejected(FutureEmploymentLayout.forModule(GAME_PASS), PlayerSnapshotValidator::validateFutureEmploymentLayout);
        assertNumericDriftRejected(FutureTransferLayout.forModule(GAME_PASS), PlayerSnapshotValidator::validateFutureTransferLayout);
        assertNumericDriftRejected(NonPlayingProfileLayout.forModule(GAME_PASS), PlayerSnapshotValidator::validateNonPlayingProfileLayout);
        assertThatCode(() -> PlayerSnapshotValidator.validateContractLayout(ContractRecordLayout.forModule(GAME_PASS)))
                .doesNotThrowAnyException();
    }

    @Test
    void persistedGamePassPayloadsDecodeLikeSteamPayloads() {
        int clock = (2026 << 16) | 335;
        String ledger = "00".repeat(ClubFinancialLedgerLayout.current().payloadBytes());
        assertThat(ClubFinancialLedgerLayout.values(ledger, clock, GAME_PASS))
                .isEqualTo(ClubFinancialLedgerLayout.values(ledger, clock, NativeModuleProfile.FM27_SHA256));
        assertThat(NonPlayingProfileLayout.values("00".repeat(52), GAME_PASS))
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
