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
}
