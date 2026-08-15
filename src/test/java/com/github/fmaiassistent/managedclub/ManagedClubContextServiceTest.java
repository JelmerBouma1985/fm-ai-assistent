package com.github.fmaiassistent.managedclub;

import com.github.fmaiassistent.domain.entity.ClubEntity;
import com.github.fmaiassistent.repository.ClubRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagedClubContextServiceTest {
    @Test
    void enrichesEachConversationOnceWithRamAndDatabaseClubContext() throws Exception {
        ManagedClubMemoryReader memory = mock(ManagedClubMemoryReader.class);
        ClubRepository clubs = mock(ClubRepository.class);
        ClubEntity club = mock(ClubEntity.class);
        when(memory.read(42, 0x238bdd, null)).thenReturn(new ManagedClubIdentity(
                0x1000, "Jelmer Bouma", 0x2000, 0x3000, "sc Heerenveen"));
        when(clubs.findFirstBySourceAddressOrderByReputationDesc(0x3000L)).thenReturn(Optional.of(club));
        when(club.getCompetition()).thenReturn("Eredivisie");
        when(club.getNation()).thenReturn("Netherlands");
        when(club.getGender()).thenReturn("male");
        when(club.getReputation()).thenReturn(7200);
        when(club.getBalance()).thenReturn(12_000_000L);
        when(club.getTransferBudget()).thenReturn(4_500_000L);
        when(club.getPayrollBudget()).thenReturn(240_000L);
        ManagedClubContextService service = new ManagedClubContextService(memory, clubs);

        ManagedClubContext detected = service.refresh(42, 0x238bdd, null);

        assertThat(detected.managerName()).isEqualTo("Jelmer Bouma");
        assertThat(detected.clubName()).isEqualTo("sc Heerenveen");
        assertThat(service.enrich("codex:one", "Assess my squad"))
                .contains("<fm26_managed_club_context>")
                .contains("Human manager: Jelmer Bouma")
                .contains("Managed club: sc Heerenveen")
                .contains("Competition: Eredivisie")
                .contains("Transfer budget: £4,500,000")
                .contains("Assess my squad");
        assertThat(service.enrich("codex:one", "Follow up")).isEqualTo("Follow up");
        assertThat(service.enrich("copilot:two", "Another provider"))
                .contains("Managed club: sc Heerenveen");

        service.setAiContextEnabled(false);
        assertThat(service.current().clubName()).isEqualTo("sc Heerenveen");
        assertThat(service.enrich("codex:disabled", "Do not include it"))
                .isEqualTo("Do not include it");

        service.setAiContextEnabled(true);
        assertThat(service.enrich("codex:one", "Include it again"))
                .contains("Managed club: sc Heerenveen")
                .contains("Include it again");
    }

    @Test
    void unavailableDetectionIsVisibleButNotSentAsFactualContext() {
        ManagedClubContextService service = new ManagedClubContextService(
                mock(ManagedClubMemoryReader.class), mock(ClubRepository.class));

        ManagedClubContext context = service.markUnavailable("No active human manager was found");

        assertThat(context.state()).isEqualTo(ManagedClubContext.State.UNAVAILABLE);
        assertThat(context.message()).isEqualTo("No active human manager was found");
        assertThat(service.enrich("codex:one", "hello")).isEqualTo("hello");
    }
}
