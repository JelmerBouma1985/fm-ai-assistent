package com.github.fmaiassistent.tools;

import com.github.fmaiassistent.repository.LoadMetadataRepository;
import com.github.fmaiassistent.managedclub.ManagedClubContext;
import com.github.fmaiassistent.managedclub.ManagedClubContextService;
import com.github.fmaiassistent.tactic.TacticContext;
import com.github.fmaiassistent.tactic.TacticContextService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ToolAvailabilityTest {
    private final LoadMetadataRepository metadata = mock(LoadMetadataRepository.class);
    private final TacticContextService tactics = mock(TacticContextService.class);
    private final ManagedClubContextService clubs = mock(ManagedClubContextService.class);
    private final ToolAvailability availability = new ToolAvailability(metadata, tactics, clubs);

    @Test void missingSnapshotAndTacticHaveDistinctCheapRemedies() {
        assertThat(availability.unavailable(tool(true, true, false, false))).contains("Needs game data");
        verifyNoInteractions(tactics, clubs);
        when(metadata.existsById("snapshot_id")).thenReturn(true);
        when(tactics.current()).thenReturn(mock(TacticContext.class));
        assertThat(availability.unavailable(tool(true, true, false, false))).contains("Needs tactic");
        verifyNoInteractions(clubs);
    }
    @Test void metadataToolsDoNotNeedDataAndNeverExecuteAnything() {
        assertThat(availability.unavailable(tool(false, false, false, false))).isEmpty();
        verifyNoInteractions(metadata, tactics, clubs);
    }
    @Test void fixturesRequireEmployedClubAndEvidenceRequiresKnownCareer() {
        when(clubs.current()).thenReturn(ManagedClubContext.unemployed(1, "Test manager", 1L));
        assertThat(availability.unavailable(tool(false, false, true, false))).contains("Needs managed club");
        assertThat(availability.unavailable(tool(false, false, false, true))).contains("Needs career identity");
        when(clubs.currentCareerKey()).thenReturn("career-test");
        assertThat(availability.unavailable(tool(false, false, false, true))).isEmpty();
        verifyNoInteractions(metadata, tactics);
    }
    private static ToolRegistry.Descriptor tool(boolean snapshot, boolean tactic, boolean club, boolean career) {
        return new ToolRegistry.Descriptor("test", "Test", "Test", "{\"type\":\"object\"}",
                ToolInfo.Category.OTHER, ToolInfo.Effect.READ_ONLY, snapshot, tactic, club, career);
    }
}
