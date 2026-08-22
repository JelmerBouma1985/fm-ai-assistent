package com.github.fmaiassistent.recruitment;

import com.github.fmaiassistent.domain.entity.LoadMetadataEntity;
import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.domain.entity.RecruitmentCaseEntity;
import com.github.fmaiassistent.exporter.PlayerExporter;
import com.github.fmaiassistent.repository.LoadMetadataRepository;
import com.github.fmaiassistent.repository.PlayerRepository;
import com.github.fmaiassistent.repository.RecruitmentCaseRepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecruitmentCaseServiceTest {
    @Test
    void storesVerifiedEvidenceAndPreservesFieldsOnPartialUpdates() {
        RecruitmentCaseRepository cases = mock(RecruitmentCaseRepository.class);
        PlayerRepository players = mock(PlayerRepository.class);
        LoadMetadataRepository metadata = mock(LoadMetadataRepository.class);
        RecruitmentCaseService service = new RecruitmentCaseService(cases, players, metadata);
        PlayerEntity player = player("Target", 2002000001L);
        when(players.findFirstByUniqueId(2002000001L)).thenReturn(Optional.of(player));
        when(metadata.findById("game_date")).thenReturn(Optional.of(new LoadMetadataEntity("game_date", "2029-07-01")));
        when(cases.findById(2002000001L)).thenReturn(Optional.empty());
        when(cases.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> created = service.update(
                2002000001L, "interested", "bid_accepted", 8_000_000L, 25_000L,
                "Agent confirmed", "agent_enquiry", null);

        assertThat(created).containsEntry("interest_status", "interested")
                .containsEntry("deal_stage", "bid_accepted")
                .containsEntry("quoted_fee", 8_000_000L)
                .containsEntry("observed_game_date", "2029-07-01")
                .containsEntry("stale", false);

        RecruitmentCaseEntity existing = new RecruitmentCaseEntity(2002000001L);
        existing.update("interested", "bid_accepted", 8_000_000L, 25_000L,
                "Agent confirmed", "agent_enquiry", "2029-07-01");
        when(cases.findById(2002000001L)).thenReturn(Optional.of(existing));
        Map<String, Object> updated = service.update(
                2002000001L, null, "contract_agreed", null, null,
                null, null, null);

        assertThat(updated).containsEntry("interest_status", "interested")
                .containsEntry("deal_stage", "contract_agreed")
                .containsEntry("quoted_fee", 8_000_000L)
                .containsEntry("quoted_weekly_wage", 25_000L);
    }

    private static PlayerEntity player(String name, long uniqueId) {
        Map<String, Object> row = new HashMap<>();
        PlayerExporter.FIELD_NAMES.forEach(field -> row.put(field, null));
        row.put("name", name);
        row.put("unique_id", uniqueId);
        return PlayerEntity.fromExportRow(row);
    }
}
