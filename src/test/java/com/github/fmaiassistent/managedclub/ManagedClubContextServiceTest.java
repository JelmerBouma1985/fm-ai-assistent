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
    void nationalJobIsAvailableForAdviceWithoutAClubOrTransferBudget() throws Exception {
        ManagedClubMemoryReader memory = mock(ManagedClubMemoryReader.class);
        ClubRepository clubs = mock(ClubRepository.class);
        var nation = new ManagedNationalTeam(1, 2, 3, 784, "Netherlands");
        when(memory.read(42, com.github.fmaiassistent.linux.FmOffsets.DEFAULT_BUILD, null))
                .thenReturn(new ManagedClubIdentity(0x1000, 4242L, "Jelmer Bouma", 0, 0, "", nation));
        ManagedClubContextService service = new ManagedClubContextService(memory, clubs);
        ManagedClubContext context = service.refresh(42, com.github.fmaiassistent.linux.FmOffsets.DEFAULT_BUILD, null);
        assertThat(context.state()).isEqualTo(ManagedClubContext.State.NATIONAL_TEAM);
        assertThat(context.available()).isFalse();
        assertThat(context.adviceAvailable()).isTrue();
        assertThat(context.careerKey()).isEqualTo("4242:jelmer-bouma");
        assertThat(context.transferBudget()).isNull();
        org.mockito.Mockito.verifyNoInteractions(clubs);
        assertThat(service.contextFor("chat")).contains("Managed national team: Netherlands", "primary nationality", "fm27_get_national_team_context")
                .doesNotContain("unemployed", "Transfer budget:");
    }

    @Test
    void unemploymentPreservesCareerAndReplacesPreviousClubContextOnlyAfterPublish() throws Exception {
        ManagedClubMemoryReader memory = mock(ManagedClubMemoryReader.class);
        ClubRepository clubs = mock(ClubRepository.class);
        when(memory.read(42, com.github.fmaiassistent.linux.FmOffsets.DEFAULT_BUILD, null))
                .thenReturn(new ManagedClubIdentity(0x1000, 4242L, "Jelmer Bouma", 0x2000, 0x3000, "sc Heerenveen"))
                .thenReturn(new ManagedClubIdentity(0x1000, 4242L, "Jelmer Bouma", 0, 0, ""));
        ManagedClubContextService service = new ManagedClubContextService(memory, clubs);
        ManagedClubContext employed = service.refresh(42, com.github.fmaiassistent.linux.FmOffsets.DEFAULT_BUILD, null);
        assertThat(service.contextFor("chat")).contains("Managed club: sc Heerenveen");

        ManagedClubContext unemployed = service.detect(42, com.github.fmaiassistent.linux.FmOffsets.DEFAULT_BUILD, null);
        assertThat(service.current()).isSameAs(employed);
        service.publish(unemployed);

        assertThat(unemployed.state()).isEqualTo(ManagedClubContext.State.UNEMPLOYED);
        assertThat(unemployed.available()).isFalse();
        assertThat(unemployed.careerKey()).isEqualTo(employed.careerKey());
        assertThat(unemployed.clubAddress()).isNull();
        assertThat(service.contextFor("chat")).contains("Employment status: unemployed", "Managed club: none", "transfers")
                .doesNotContain("sc Heerenveen");
        assertThat(service.contextFor("chat")).isEmpty();
    }

    @Test
    void enrichesEachConversationOnceWithRamAndDatabaseClubContext() throws Exception {
        ManagedClubMemoryReader memory = mock(ManagedClubMemoryReader.class);
        ClubRepository clubs = mock(ClubRepository.class);
        ClubEntity club = mock(ClubEntity.class);
        when(memory.read(42, com.github.fmaiassistent.linux.FmOffsets.DEFAULT_BUILD, null)).thenReturn(new ManagedClubIdentity(
                0x1000, 4242L, "Jelmer Bouma", 0x2000, 0x3000, "sc Heerenveen"));
        when(clubs.findFirstBySourceAddressOrderByReputationDesc(0x3000L)).thenReturn(Optional.of(club));
        when(club.getCompetition()).thenReturn("Eredivisie");
        when(club.getNation()).thenReturn("Netherlands");
        when(club.getGender()).thenReturn("male");
        when(club.getReputation()).thenReturn(7200);
        when(club.getBalance()).thenReturn(12_000_000L);
        when(club.getTransferBudget()).thenReturn(4_500_000L);
        when(club.getPayrollBudget()).thenReturn(240_000L);
        ManagedClubContextService service = new ManagedClubContextService(memory, clubs);

        ManagedClubContext detected = service.refresh(42, com.github.fmaiassistent.linux.FmOffsets.DEFAULT_BUILD, null);

        assertThat(detected.managerName()).isEqualTo("Jelmer Bouma");
        assertThat(detected.careerKey()).isEqualTo("4242:jelmer-bouma");
        assertThat(detected.clubName()).isEqualTo("sc Heerenveen");
        assertThat(service.enrich("codex:one", "Assess my squad"))
                .contains("<fm27_managed_club_context>")
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
