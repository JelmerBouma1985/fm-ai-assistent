package com.github.fmaiassistent.antigravity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AntigravityProcessRunnerTest {
    @TempDir
    Path workspace;

    @Test
    void consumesStdoutAndStderrBeforeCompleting() throws Exception {
        FakeProcess process = new FakeProcess("""
                {"event":"init","conversation_id":"abc","init":{"cwd":"/workspace"}}
                {"event":"result","result":{"conversation_id":"abc","status":"SUCCESS","response":"OK"}}
                """, "diagnostic line\n", 0);
        AntigravityProcessRunner runner = runner(process);
        List<AntigravityStreamEvent> events = new CopyOnWriteArrayList<>();

        AntigravityProcessResult result = runner.start(
                        "turn-1", List.of("agy"), events::add)
                .completion().get(1, TimeUnit.SECONDS);

        assertEquals(2, events.size());
        assertTrue(result.resultReceived());
        assertTrue(result.stderr().contains("diagnostic line"));
    }

    @Test
    void cancellationTerminatesOnlyThatTurn() throws Exception {
        FakeProcess process = new FakeProcess("", "", null);
        AntigravityProcessRunner runner = runner(process);

        AntigravityTurnHandle handle = runner.start("turn-1", List.of("agy"), ignored -> { });
        handle.cancel();
        AntigravityProcessResult result = handle.completion().get(1, TimeUnit.SECONDS);

        assertTrue(process.terminated);
        assertTrue(result.cancelled());
    }

    private AntigravityProcessRunner runner(FakeProcess process) throws Exception {
        AntigravityProcessLauncher launcher = mock(AntigravityProcessLauncher.class);
        when(launcher.launch(List.of("agy"), workspace)).thenReturn(process);
        AntigravityWorkspaceResolver resolver = mock(AntigravityWorkspaceResolver.class);
        when(resolver.workingDirectory()).thenReturn(workspace);
        AntigravityProperties properties = new AntigravityProperties(
                true, "agy", ".", Duration.ofMinutes(15), Duration.ofMillis(50),
                null, null, null, false);
        return new AntigravityProcessRunner(
                launcher,
                new AntigravityStreamParser(JsonMapper.builder().build()),
                resolver,
                properties);
    }

    private static final class FakeProcess implements AntigravityManagedProcess {
        private final BufferedReader stdout;
        private final BufferedReader stderr;
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private volatile boolean terminated;

        private FakeProcess(String stdout, String stderr, Integer exitCode) {
            this.stdout = new BufferedReader(new StringReader(stdout));
            this.stderr = new BufferedReader(new StringReader(stderr));
            if (exitCode != null) {
                exit.complete(exitCode);
            }
        }

        @Override
        public BufferedReader stdout() {
            return stdout;
        }

        @Override
        public BufferedReader stderr() {
            return stderr;
        }

        @Override
        public long pid() {
            return 123;
        }

        @Override
        public boolean isAlive() {
            return !exit.isDone();
        }

        @Override
        public CompletableFuture<Integer> onExit() {
            return exit;
        }

        @Override
        public void terminate(Duration timeout) {
            terminated = true;
            exit.complete(143);
        }
    }
}
