package com.github.fmaiassistent.tactic;

import com.github.fmaiassistent.domain.entity.TacticContextEntity;
import com.github.fmaiassistent.repository.TacticContextRepository;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TacticContextServiceTest {
    @Test
    void uploadedFmfIsDecodedAndEnrichesAgentPrompt() {
        TacticContextService service = service();

        TacticContext context = service.loadUploads(
                Map.of("tactic.fmf", FmfTacticParserTest.fmf("4-2-4-press")));

        assertThat(context.title()).isEqualTo("4-2-4-press");
        assertThat(context.importedFiles()).containsExactly("tactic.fmf");
        assertThat(context.warnings()).isEmpty();
        assertThat(context.definition()).isNotNull();
        assertThat(context.definition().slots()).hasSize(1);
        assertThat(context.definition().slots().getFirst().inPossession().role())
                .isEqualTo("Ball-Playing Goalkeeper");
        assertThat(context.definition().slots().getFirst().outOfPossession().role())
                .isEqualTo("Sweeper Keeper");
        assertThat(context.markdown())
                .contains("4-2-4-press.tac")
                .contains("Ball-Playing Goalkeeper (Support)")
                .contains("Sweeper Keeper (Attack)");
        assertThat(service.enrich("codex:thread-1", "How can I improve it?"))
                .contains("<fm26_tactic_context>")
                .contains("How can I improve it?");
        assertThat(service.enrich("codex:thread-1", "And defensively?"))
                .isEqualTo("And defensively?");
        assertThat(service.enrich("antigravity:conversation-1", "Review this"))
                .contains("<fm26_tactic_context>");
    }

    @Test
    void rejectsAnythingOtherThanOneFmfUpload() {
        TacticContextService service = service();

        assertThatThrownBy(() -> service.loadUploads(Map.of(
                "tactic.xml", "<tactic/>".getBytes())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only a Football Manager .fmf tactic file can be uploaded");
        assertThatThrownBy(() -> service.loadUploads(Map.of(
                "one.fmf", new byte[]{1}, "two.fmf", new byte[]{2})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Upload exactly one Football Manager .fmf tactic file");
    }

    @Test
    void uploadedContextCanBeCleared() {
        TacticContextService service = service();
        service.loadUploads(Map.of("tactic.fmf", FmfTacticParserTest.fmf("press")));

        assertThat(service.clear().active()).isFalse();
        assertThat(service.enrich("copilot:session", "hello")).isEqualTo("hello");
    }

    @Test
    void contextCanBeDisabledAndReenabledWithoutClearingTheTactic() {
        TacticContextService service = service();
        service.loadUploads(Map.of("tactic.fmf", FmfTacticParserTest.fmf("press")));
        assertThat(service.enrich("codex:thread", "first")).contains("<fm26_tactic_context>");

        service.setAiContextEnabled(false);
        assertThat(service.current().active()).isTrue();
        assertThat(service.enrich("codex:other", "disabled")).isEqualTo("disabled");

        service.setAiContextEnabled(true);
        assertThat(service.enrich("codex:thread", "enabled again"))
                .contains("<fm26_tactic_context>")
                .contains("enabled again");
    }

    @Test
    void persistsFingerprintAndRestoresTheUploadedTactic() {
        TacticContextRepository repository = mock(TacticContextRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        TacticContextService first = service(repository);

        TacticContext loaded = first.loadUploads(Map.of(
                "persistent.fmf", FmfTacticParserTest.fmf("persistent-press")));

        assertThat(loaded.fingerprint()).hasSize(64);
        var captor = org.mockito.ArgumentCaptor.forClass(TacticContextEntity.class);
        verify(repository).save(captor.capture());
        when(repository.findById(1)).thenReturn(Optional.of(captor.getValue()));
        TacticContextService restarted = service(repository);
        restarted.restorePersistedTactic();

        assertThat(restarted.current().active()).isTrue();
        assertThat(restarted.current().title()).isEqualTo("persistent-press");
        assertThat(restarted.current().fingerprint()).isEqualTo(loaded.fingerprint());

        restarted.clear();
        verify(repository).deleteById(1);
    }

    @Test
    void corruptPersistedTacticFailsClosedWithoutBreakingStartup() {
        TacticContextRepository repository = mock(TacticContextRepository.class);
        when(repository.findById(1)).thenReturn(Optional.of(
                new TacticContextEntity("broken.fmf", new byte[] {1, 2, 3}, "old-fingerprint")));
        TacticContextService service = service(repository);

        service.restorePersistedTactic();

        assertThat(service.current().active()).isFalse();
        assertThat(service.current().warnings()).singleElement()
                .asString().contains("upload it again");
    }

    @Test
    void diskTacticIsListedLoadedAndRemembered(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        byte[] fmf = FmfTacticParserTest.fmf("disk-press");
        java.nio.file.Path file = dir.resolve("disk-press.fmf");
        java.nio.file.Files.write(file, fmf);
        TacticContextRepository repository = mock(TacticContextRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        TacticContextService service = service(repository, dir);

        assertThat(service.listDiskTactics())
                .extracting(TacticDirectoryResolver.DiskTactic::fileName)
                .containsExactly("disk-press.fmf");

        TacticContext loaded = service.loadFromDisk(file);

        assertThat(loaded.active()).isTrue();
        assertThat(loaded.title()).isEqualTo("disk-press");
        assertThat(loaded.source()).startsWith("local disk");
        assertThat(loaded.markdown()).contains("Source: disk ");
        var captor = org.mockito.ArgumentCaptor.forClass(TacticContextEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSourceKind()).isEqualTo("DISK");
        assertThat(captor.getValue().getSourcePath()).isEqualTo(file.toAbsolutePath().normalize().toString());

        // Restart with unchanged file restores the remembered tactic.
        when(repository.findById(1)).thenReturn(Optional.of(captor.getValue()));
        TacticContextService restarted = service(repository, dir);
        restarted.restorePersistedTactic();

        assertThat(restarted.current().active()).isTrue();
        assertThat(restarted.current().title()).isEqualTo("disk-press");
        assertThat(restarted.current().fingerprint()).isEqualTo(loaded.fingerprint());
    }

    @Test
    void changedDiskFileIsSilentlyReloadedOnRestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Files.write(dir.resolve("press.fmf"), FmfTacticParserTest.fmf("press-v1"));
        TacticContextRepository repository = mock(TacticContextRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        TacticContextService service = service(repository, dir);
        TacticContext loaded = service.loadFromDisk(dir.resolve("press.fmf"));

        // FM26 save overwrites the file with a new tactic.
        java.nio.file.Files.write(dir.resolve("press.fmf"), FmfTacticParserTest.fmf("press-v2"));
        var captor = org.mockito.ArgumentCaptor.forClass(TacticContextEntity.class);
        verify(repository).save(captor.capture());
        when(repository.findById(1)).thenReturn(Optional.of(captor.getValue()));
        TacticContextService restarted = service(repository, dir);
        restarted.restorePersistedTactic();

        assertThat(restarted.current().active()).isTrue();
        assertThat(restarted.current().title()).isEqualTo("press-v2");
        assertThat(restarted.current().fingerprint()).isNotEqualTo(loaded.fingerprint());
        // Silent reload persists the new bytes for the next start.
        verify(repository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void missingDiskFileFallsBackToCachedCopy(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        byte[] fmf = FmfTacticParserTest.fmf("cached-press");
        TacticContextEntity saved = new TacticContextEntity(
                "cached-press.fmf", fmf, sha256(fmf), "DISK", dir.resolve("gone.fmf").toString());
        TacticContextRepository repository = mock(TacticContextRepository.class);
        when(repository.findById(1)).thenReturn(Optional.of(saved));
        TacticContextService service = service(repository, dir);

        service.restorePersistedTactic();

        assertThat(service.current().active()).isTrue();
        assertThat(service.current().title()).isEqualTo("cached-press");
        assertThat(service.current().warnings()).singleElement().asString().contains("cached copy");
    }

    @Test
    void rejectsDiskPathsOutsideTheTacticsFolder(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        TacticContextRepository repository = mock(TacticContextRepository.class);
        TacticContextService service = service(repository, dir);
        java.nio.file.Path outside = dir.getParent().resolve("outside.fmf");
        java.nio.file.Files.write(outside, FmfTacticParserTest.fmf("outside"));

        assertThatThrownBy(() -> service.loadFromDisk(outside))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the FM26 tactics folder");
    }

    private static TacticContextService service() {
        TacticContextProperties properties = new TacticContextProperties(
                DataSize.ofMegabytes(20), 16_000);
        return new TacticContextService(new FmfTacticParser(), properties);
    }

    private static TacticContextService service(TacticContextRepository repository) {
        TacticContextProperties properties = new TacticContextProperties(
                DataSize.ofMegabytes(20), 16_000);
        return new TacticContextService(new FmfTacticParser(), properties, repository);
    }

    private static TacticContextService service(TacticContextRepository repository, java.nio.file.Path diskDirectory) {
        TacticContextProperties properties = new TacticContextProperties(
                DataSize.ofMegabytes(20), 16_000);
        return new TacticContextService(new FmfTacticParser(), properties, repository, diskDirectory);
    }

    private static String sha256(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
