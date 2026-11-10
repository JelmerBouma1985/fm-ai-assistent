package com.github.fmaiassistent.exporter;

import com.github.fmaiassistent.memory.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class Fm2701UpdateTest {
    private static final String HASH = NativeModuleProfile.FM27_2701_SHA256;

    @Test
    void replaysAllFourContractTypesAndFutureEmploymentFromTheUpdatedProcess() throws Exception {
        var mapper = JsonMapper.builder().build();
        try (var in = Files.newInputStream(Path.of("src/test/resources/fm27/contracts-2701-captured.json"))) {
            byte[] bytes = in.readNBytes(262145);
            assertThat(bytes.length).isLessThanOrEqualTo(262144);
            var kinds = new HashSet<String>();
            for (var fixture : mapper.readTree(bytes)) {
                var layout = ContractRecordLayout.forModule(HASH);
                var exporter = Fm27CapturedContractsTest.exporter(fixture, layout);
                long person = fixture.path("person").asLong(), uid = fixture.path("uid").asLong();
                String kind = fixture.path("kind").asString();
                kinds.add(kind);
                if (kind.equals("FUTURE")) {
                    var future = exporter.readFutureEmployment(person, uid, fixture.path("offer").asInt()).orElseThrow();
                    assertThat(mapper.writeValueAsString(future)).isEqualTo(fixture.path("future").toString());
                    for (var drift : shifted(FutureEmploymentLayout.forModule(HASH))) {
                        assertThatThrownBy(() -> exporter.readFutureEmployment(person, uid,
                                fixture.path("offer").asInt(), (FutureEmploymentLayout) drift))
                                .isInstanceOf(IOException.class).hasMessageContaining("layout anchor");
                    }
                } else {
                    var contracts = exporter.read(person, uid);
                    assertThat(contracts).anyMatch(c -> c.kind().equals(kind));
                    assertThat(mapper.writeValueAsString(contracts)).isEqualTo(fixture.path("contracts").toString());
                    // The same captured inputs cannot be decoded using stale roots/types.
                    assertThat(Fm27CapturedContractsTest.exporter(fixture,
                            ContractRecordLayout.current()).read(person, uid))
                            .allSatisfy(c -> assertThat(c.kind()).startsWith("UNSUPPORTED_"));
                    for (var drift : shifted(layout)) {
                        assertThatThrownBy(() -> Fm27CapturedContractsTest.exporter(fixture,
                                (ContractRecordLayout) drift).read(person, uid))
                                .isInstanceOf(IOException.class).hasMessageContaining("layout anchor");
                    }
                }
            }
            assertThat(kinds).containsExactlyInAnyOrder("FULL", "LOAN", "BASIC", "B_CLUB", "FUTURE");
        }
    }

    @Test
    void eachUpdatedLayoutRejectsDriftAndUnknownImages() throws Exception {
        var layouts = List.of(
                AwardNameLayout.forModule(HASH), ClubFinanceLayout.forModule(HASH),
                ClubFinancialCompetitionLayout.forModule(HASH), ClubFinancialLedgerLayout.forModule(HASH),
                ClubFinancialLoanLayout.forModule(HASH), ClubIdentityLayout.forModule(HASH),
                ClubUefaCoefficientLayout.forModule(HASH), ContractRecordLayout.forModule(HASH),
                FutureEmploymentLayout.forModule(HASH), FutureTransferLayout.forModule(HASH),
                NonPlayingProfileLayout.forModule(HASH), ClubFinancialHistoryLayout.forModule(HASH));
        for (var layout : layouts) {
            validate(layout);
            for (var drift : shifted(layout)) {
                assertThatThrownBy(() -> validate(drift)).as(layout.getClass().getSimpleName())
                        .isInstanceOf(IOException.class);
            }
            var factory = layout.getClass().getMethod("forModule", String.class);
            assertThatThrownBy(() -> factory.invoke(null, "unverified-image"))
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
        assertThat(NativeModuleProfile.supported(HASH)).isTrue();
        assertThat(NativeModuleProfile.supported(NativeModuleProfile.FM27_SHA256)).isTrue();
        assertThat(NativeModuleProfile.supported("unverified-image")).isFalse();
    }

    private static void validate(Record layout) throws IOException {
        switch (layout) {
            case AwardNameLayout l -> PlayerSnapshotValidator.validateAwardNameLayout(l);
            case ClubFinanceLayout l -> PlayerSnapshotValidator.validateClubFinanceLayout(ClubRecordLayout.current(), l);
            case ClubFinancialCompetitionLayout l -> PlayerSnapshotValidator.validateClubFinancialCompetitionLayout(l);
            case ClubFinancialLedgerLayout l -> PlayerSnapshotValidator.validateClubFinancialLedgerLayout(l);
            case ClubFinancialLoanLayout l -> PlayerSnapshotValidator.validateClubFinancialLoanLayout(l);
            case ClubIdentityLayout l -> PlayerSnapshotValidator.validateClubIdentityLayout(l);
            case ClubUefaCoefficientLayout l -> PlayerSnapshotValidator.validateClubUefaLayout(l);
            case ContractRecordLayout l -> PlayerSnapshotValidator.validateContractLayout(l);
            case FutureEmploymentLayout l -> PlayerSnapshotValidator.validateFutureEmploymentLayout(l);
            case FutureTransferLayout l -> PlayerSnapshotValidator.validateFutureTransferLayout(l);
            case NonPlayingProfileLayout l -> PlayerSnapshotValidator.validateNonPlayingProfileLayout(l);
            case ClubFinancialHistoryLayout l -> PlayerSnapshotValidator.validateClubFinancialHistoryLayout(l);
            default -> throw new IllegalArgumentException("Unexpected layout");
        }
    }

    /** Perturb one component at a time, including nested native layouts. */
    private static List<Record> shifted(Record original) throws Exception {
        var components = original.getClass().getRecordComponents();
        var ctor = original.getClass().getDeclaredConstructor(Arrays.stream(components)
                .map(RecordComponent::getType).toArray(Class<?>[]::new));
        var variants = new ArrayList<Record>();
        for (int i = 0; i < components.length; i++) {
            Object[] args = new Object[components.length];
            for (int j = 0; j < args.length; j++) args[j] = components[j].getAccessor().invoke(original);
            List<?> changes;
            if (args[i] instanceof Integer n) changes = List.of(n + 1);
            else if (args[i] instanceof Long n) changes = List.of(n + 8);
            else if (args[i] instanceof Record nested) changes = shifted(nested);
            else continue;
            for (var change : changes) {
                var modified = args.clone(); modified[i] = change;
                variants.add((Record) ctor.newInstance(modified));
            }
        }
        return variants;
    }
}



