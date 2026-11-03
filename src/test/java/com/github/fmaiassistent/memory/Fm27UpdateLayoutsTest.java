package com.github.fmaiassistent.memory;

import com.github.fmaiassistent.exporter.PlayerSnapshotValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Fm27UpdateLayoutsTest {
    private static final String UPDATED = NativeModuleProfile.UPDATED_SHA256;

    @Test
    void verifiedImageUsesItsCapturedRootsAndKeepsLegacyFactories() {
        assertThat(NativeModuleProfile.supported(UPDATED)).isTrue();
        assertThat(NativeModuleProfile.supported(NativeModuleProfile.FM27_SHA256)).isTrue();
        assertThat(NativeModuleProfile.supported(null)).isFalse();
        assertThat(ClubIdentityLayout.current().registryRva()).isEqualTo(0x57036e0);
        assertThat(ClubIdentityLayout.forModule(UPDATED).registryRva()).isEqualTo(0x57056e0);
        assertThat(ClubIdentityLayout.forModule(UPDATED).clubVtable()).isEqualTo(0x4cdc1f8);
        assertThat(ContractRecordLayout.forModule(UPDATED).full().vtable()).isEqualTo(0x4b02ae8);
        assertThat(FutureEmploymentLayout.forModule(UPDATED).gameDateRva()).isEqualTo(0x56af378);
        assertThat(FutureTransferLayout.forModule(UPDATED).managerRoot()).isEqualTo(0x56f4ef0);
        assertThat(ClubUefaCoefficientLayout.forModule(UPDATED).ruleVersionRva()).isEqualTo(0x56c412a);
        assertThat(NonPlayingProfileLayout.forModule(UPDATED).personToMemberRel())
                .isEqualTo(NonPlayingProfileLayout.current().personToMemberRel());
        assertThat(ClubFinancialHistoryLayout.forModule(UPDATED).ledger())
                .isEqualTo(ClubFinancialLedgerLayout.forModule(UPDATED));
    }

    @Test
    void everyProfileFactoryRejectsAnUnknownImage() {
        List<Function<String, ?>> factories = List.of(
                ClubIdentityLayout::forModule, ClubFinanceLayout::forModule,
                ClubFinancialLedgerLayout::forModule, ClubFinancialLoanLayout::forModule,
                ClubFinancialCompetitionLayout::forModule, ClubUefaCoefficientLayout::forModule,
                ContractRecordLayout::forModule, AwardNameLayout::forModule,
                FutureEmploymentLayout::forModule, FutureTransferLayout::forModule,
                NonPlayingProfileLayout::forModule, ClubFinancialHistoryLayout::forModule);
        for (var factory : factories) {
            assertThatThrownBy(() -> factory.apply("unverified-image"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void newImageLayoutAnchorsRejectEveryShiftedNumericDisplacement() throws Exception {
        assertNumericDriftRejected(ClubIdentityLayout.forModule(UPDATED), PlayerSnapshotValidator::validateClubIdentityLayout);
        assertNumericDriftRejected(ClubFinanceLayout.forModule(UPDATED),
                layout -> PlayerSnapshotValidator.validateClubFinanceLayout(ClubRecordLayout.current(), layout));
        assertNumericDriftRejected(ClubFinancialLedgerLayout.forModule(UPDATED), PlayerSnapshotValidator::validateClubFinancialLedgerLayout);
        assertNumericDriftRejected(ClubFinancialLoanLayout.forModule(UPDATED), PlayerSnapshotValidator::validateClubFinancialLoanLayout);
        assertNumericDriftRejected(ClubFinancialCompetitionLayout.forModule(UPDATED), PlayerSnapshotValidator::validateClubFinancialCompetitionLayout);
        assertNumericDriftRejected(ClubUefaCoefficientLayout.forModule(UPDATED), PlayerSnapshotValidator::validateClubUefaLayout);
        assertNumericDriftRejected(AwardNameLayout.forModule(UPDATED), PlayerSnapshotValidator::validateAwardNameLayout);
        assertNumericDriftRejected(FutureEmploymentLayout.forModule(UPDATED), PlayerSnapshotValidator::validateFutureEmploymentLayout);
        assertNumericDriftRejected(FutureTransferLayout.forModule(UPDATED), PlayerSnapshotValidator::validateFutureTransferLayout);
        assertNumericDriftRejected(NonPlayingProfileLayout.forModule(UPDATED), PlayerSnapshotValidator::validateNonPlayingProfileLayout);
        var contracts = ContractRecordLayout.forModule(UPDATED);
        PlayerSnapshotValidator.validateContractLayout(contracts);
        var mixed = new ContractRecordLayout(ContractRecordLayout.current().links(), contracts.full(), contracts.loan(),
                contracts.sharedVectors(), contracts.fullTerm(), contracts.sharedTerm(), contracts.bClub(), contracts.basic(),
                contracts.award(), contracts.teamOwner());
        assertThatThrownBy(() -> PlayerSnapshotValidator.validateContractLayout(mixed)).isInstanceOf(IOException.class);
    }

    @Test
    void sameMemberFixtureRejectsShiftedUpdatedProfileBeforePublication() throws Exception {
        var layout = NonPlayingProfileLayout.forModule(UPDATED);
        long base = 0x7ffca8d50000L;
        byte[] member = new byte[layout.memberBytes()];
        ByteBuffer.wrap(member).order(ByteOrder.LITTLE_ENDIAN).putLong(base + layout.memberVtableRva());
        PlayerSnapshotValidator.validateNonPlayingProfile(member, base, layout);
        var shifted = new NonPlayingProfileLayout(layout.personToMemberRel(), layout.constructionRel(), layout.memberBytes(),
                layout.profileOffset() + 1, layout.profileBytes(), layout.memberVtableRva(), layout.standalonePersonVtableRva(),
                layout.combinedPersonVtableRva(), layout.humanPersonToMemberRel(), layout.humanPersonVtableRva(), layout.fields());
        assertThatThrownBy(() -> PlayerSnapshotValidator.validateNonPlayingProfile(member, base, shifted))
                .isInstanceOf(IOException.class).hasMessageContaining("anchor");
    }

    @Test
    void persistedScalarPayloadsRemainReadableForBothVerifiedImages() {
        int clock = (2026 << 16) | 215;
        String ledger = "00".repeat(ClubFinancialLedgerLayout.current().payloadBytes());
        assertThat(ClubFinancialLedgerLayout.values(ledger, clock, UPDATED))
                .isEqualTo(ClubFinancialLedgerLayout.values(ledger, clock, NativeModuleProfile.FM27_SHA256));
        assertThat(ClubFinancialLoanLayout.values("", clock, UPDATED)).isPresent();
        assertThat(ClubFinancialLedgerLayout.values(ledger, clock, "unverified-image")).isEmpty();
        assertThat(NonPlayingProfileLayout.values("00".repeat(52), UPDATED))
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
