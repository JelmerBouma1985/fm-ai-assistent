package com.github.fmaiassistent.service;

import com.github.fmaiassistent.domain.entity.LoadMetadataEntity;
import com.github.fmaiassistent.exporter.ClubExporter;
import com.github.fmaiassistent.exporter.CompetitionExporter;
import com.github.fmaiassistent.exporter.PeopleExporter;
import com.github.fmaiassistent.exporter.PlayerExporter;
import com.github.fmaiassistent.exporter.StaffExporter;
import com.github.fmaiassistent.managedclub.ManagedClubContext;
import com.github.fmaiassistent.managedclub.ManagedClubContextService;
import com.github.fmaiassistent.repository.DatabaseService;
import com.github.fmaiassistent.repository.LoadMetadataRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseLoadAllServiceTest {
    @Test
    void extractsIndependentRamTablesConcurrentlyBeforeAtomicPersistence() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch readersStarted = new CountDownLatch(3);
        AtomicBoolean databaseCleared = new AtomicBoolean(false);
        List<String> persistenceOrder = Collections.synchronizedList(new ArrayList<>());

        when(fixture.people.exportAllPeople(123, 1530, null)).thenAnswer(invocation -> {
            awaitOtherReaders(readersStarted, databaseCleared);
            return peopleResult(2, 1);
        });
        when(fixture.clubs.exportAllClubs(123, 1530, null)).thenAnswer(invocation -> {
            awaitOtherReaders(readersStarted, databaseCleared);
            return new ClubExporter.ExportResult(Collections.nCopies(3, Map.of()));
        });
        when(fixture.competitions.exportAllCompetitions(123, 1530, null)).thenAnswer(invocation -> {
            awaitOtherReaders(readersStarted, databaseCleared);
            return new CompetitionExporter.ExportResult(Collections.nCopies(4, Map.of()));
        });
        doAnswer(invocation -> {
            databaseCleared.set(true);
            return null;
        }).when(fixture.database).clearAllTables();
        when(fixture.writer.saveCompetitions(any())).thenAnswer(invocation -> {
            assertThat(databaseCleared).isTrue();
            persistenceOrder.add("competitions");
            return Map.of();
        });
        when(fixture.writer.saveClubs(any(), any())).thenAnswer(invocation -> {
            persistenceOrder.add("clubs");
            return Map.of();
        });
        doAnswer(invocation -> {
            persistenceOrder.add("players");
            return null;
        }).when(fixture.writer).savePlayers(any(), any());
        doAnswer(invocation -> {
            persistenceOrder.add("staff");
            return null;
        }).when(fixture.writer).saveStaff(any(), any());

        DatabaseLoadAllService.LoadAllResult result = fixture.service.loadAll(123, 1530, null);

        assertThat(readersStarted.getCount()).isZero();
        assertThat(persistenceOrder).containsExactly("competitions", "clubs", "players", "staff");
        assertThat(result.players()).isEqualTo(2);
        assertThat(result.staff()).isEqualTo(1);
        assertThat(result.clubs()).isEqualTo(3);
        assertThat(result.competitions()).isEqualTo(4);
        verify(fixture.metadata).saveAll(anyList());
    }

    @Test
    void ramFailureDoesNotClearThePreviousSnapshot() throws Exception {
        Fixture fixture = new Fixture();
        IOException failure = new IOException("people RAM unavailable");
        when(fixture.people.exportAllPeople(123, 1530, null)).thenThrow(failure);
        when(fixture.clubs.exportAllClubs(123, 1530, null)).thenReturn(new ClubExporter.ExportResult(List.of()));
        when(fixture.competitions.exportAllCompetitions(123, 1530, null))
                .thenReturn(new CompetitionExporter.ExportResult(List.of()));

        assertThatThrownBy(() -> fixture.service.loadAll(123, 1530, null))
                .isSameAs(failure);

        verify(fixture.database, never()).clearAllTables();
        verify(fixture.managedClubs).restore(fixture.previousContext);
    }

    @Test
    void independentTableFailureAlsoPreventsPersistence() throws Exception {
        Fixture fixture = new Fixture();
        IOException failure = new IOException("club RAM unavailable");
        when(fixture.people.exportAllPeople(123, 1530, null)).thenReturn(peopleResult(0, 0));
        when(fixture.clubs.exportAllClubs(123, 1530, null)).thenThrow(failure);
        when(fixture.competitions.exportAllCompetitions(123, 1530, null))
                .thenReturn(new CompetitionExporter.ExportResult(List.of()));

        assertThatThrownBy(() -> fixture.service.loadAll(123, 1530, null))
                .isSameAs(failure);

        verify(fixture.database, never()).clearAllTables();
        verify(fixture.managedClubs).restore(fixture.previousContext);
    }

    @Test
    void publishesManagedClubOnlyAfterTheSnapshotTransactionCommits() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.people.exportAllPeople(123, 1530, null)).thenReturn(peopleResult(0, 0));
        when(fixture.clubs.exportAllClubs(123, 1530, null)).thenReturn(new ClubExporter.ExportResult(List.of()));
        when(fixture.competitions.exportAllCompetitions(123, 1530, null))
                .thenReturn(new CompetitionExporter.ExportResult(List.of()));
        when(fixture.writer.saveCompetitions(any())).thenReturn(Map.of());
        when(fixture.writer.saveClubs(any(), any())).thenReturn(Map.of());

        TransactionSynchronizationManager.initSynchronization();
        try {
            fixture.service.loadAll(123, 1530, null);

            verify(fixture.managedClubs, never()).publish(any());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCommit());
            verify(fixture.managedClubs).publish(fixture.previousContext);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static void awaitOtherReaders(CountDownLatch readersStarted, AtomicBoolean databaseCleared)
            throws InterruptedException {
        assertThat(databaseCleared).isFalse();
        readersStarted.countDown();
        assertThat(readersStarted.await(2, TimeUnit.SECONDS)).isTrue();
    }

    private static PeopleExporter.ExportResult peopleResult(int players, int staff) {
        return new PeopleExporter.ExportResult(
                new PlayerExporter.ExportResult("2033-06-10", Collections.nCopies(players, Map.of())),
                new StaffExporter.ExportResult("2033-06-10", Collections.nCopies(staff, Map.of())),
                new PeopleExporter.Diagnostics(0, 0, 0, 0, 0, 0, 0, 0),
                16,
                12,
                players + staff,
                1,
                1);
    }

    private static final class Fixture {
        private final ClubDatabaseService clubs = mock(ClubDatabaseService.class);
        private final CompetitionDatabaseService competitions = mock(CompetitionDatabaseService.class);
        private final PeopleExporter people = mock(PeopleExporter.class);
        private final DatabaseService database = mock(DatabaseService.class);
        private final SnapshotDatabaseWriter writer = mock(SnapshotDatabaseWriter.class);
        private final ManagedClubContextService managedClubs = mock(ManagedClubContextService.class);
        private final LoadMetadataRepository metadata = mock(LoadMetadataRepository.class);
        private final ManagedClubContext previousContext = ManagedClubContext.notLoaded(7);
        private final DatabaseLoadAllService service;

        private Fixture() throws IOException {
            when(managedClubs.current()).thenReturn(previousContext);
            when(managedClubs.detect(123, 1530, null)).thenReturn(previousContext);
            when(managedClubs.publish(any())).thenAnswer(invocation -> invocation.getArgument(0));
            service = new DatabaseLoadAllService(
                    clubs, competitions, people, database, writer, managedClubs, metadata);
        }
    }
}
