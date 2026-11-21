package com.github.fmaiassistent.tools;

import com.github.fmaiassistent.mcp.FmAiAssistentTools;
import com.github.fmaiassistent.mcp.FmDecisionTools;
import com.github.fmaiassistent.mcp.FmFixtureTools;
import com.github.fmaiassistent.mcp.FmSnapshotTools;
import com.github.fmaiassistent.service.AppSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ToolRegistryTest {
    private static final String SCHEMA = """
            {"type":"object","properties":{"players":{"type":"array","items":{"type":"object",
            "properties":{"id":{"type":"integer","description":"Authoritative identity"}},"required":["id"]}}},
            "required":["players"]}
            """;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ToolAvailability availability = mock(ToolAvailability.class);
    private final ToolActivity activity = new ToolActivity();
    private ToolPolicy policy;

    @BeforeEach void setup() {
        policy = new ToolPolicy(mock(AppSettingsService.class), json);
        when(availability.unavailable(any())).thenReturn("");
    }

    @Test void everyActualRegistrationIsDiscoveredWithMatchingNestedSchemaAndAuditedEffects() {
        Object[] owners = {mock(FmAiAssistentTools.class), mock(FmSnapshotTools.class),
                mock(FmDecisionTools.class), mock(FmFixtureTools.class)};
        ToolCallback[] registered = MethodToolCallbackProvider.builder().toolObjects(owners).build().getToolCallbacks();
        ToolRegistry registry = registry(registered, owners);
        assertThat(registry.descriptors()).hasSize(23);
        assertThat(registry.descriptors()).extracting(ToolRegistry.Descriptor::id)
                .containsExactlyInAnyOrderElementsOf(List.of(registered).stream().map(c -> c.getToolDefinition().name()).toList());
        for (ToolCallback original : registered) {
            var descriptor = registry.descriptor(original.getToolDefinition().name());
            assertThat(descriptor.schema()).isEqualTo(original.getToolDefinition().inputSchema());
            assertThat(descriptor.description()).isEqualTo(original.getToolDefinition().description());
            assertThat(descriptor.effect()).isNotEqualTo(ToolInfo.Effect.UNKNOWN);
        }
        var lineup = json.readTree(registry.descriptor("fm27_optimize_lineup").schema());
        assertThat(lineup.path("properties").path("lockedAssignments").path("items")
                .path("properties").path("playerUniqueId").path("type").asString()).isEqualTo("integer");
        assertThat(registry.descriptor("fm27_refresh_data").effect()).isEqualTo(ToolInfo.Effect.APP_LOCAL_CHANGE);
        assertThat(registry.descriptor("fm27_update_recruitment_case").effect()).isEqualTo(ToolInfo.Effect.APP_LOCAL_CHANGE);
        assertThat(registry.descriptor("fm27_create_shortlist_file").effect()).isEqualTo(ToolInfo.Effect.LOCAL_FILE_EXPORT);
        assertThat(registry.descriptors().stream().filter(d -> d.effect() == ToolInfo.Effect.READ_ONLY)).hasSize(20);
        assertThat(registry.descriptor("fm27_get_role_attributes").snapshot()).isFalse();
        assertThat(registry.descriptor("fm27_optimize_lineup").tactic()).isTrue();
        assertThat(registry.descriptor("fm27_analyze_squad").tactic()).isFalse();
        assertThat(ToolInfo.Effect.values()).extracting(Enum::name).doesNotContain("GAME_WRITE");
    }

    @Test void futureCallbackAppearsAutomaticallyButUnclassifiedEffectsDefaultToDisabled() {
        ToolCallback future = callback("future.tool", SCHEMA);
        ToolRegistry registry = registry(new ToolCallback[]{future}, new Object[0]);
        var descriptor = registry.descriptor("future.tool");
        assertThat(descriptor.category()).isEqualTo(ToolInfo.Category.OTHER);
        assertThat(descriptor.effect()).isEqualTo(ToolInfo.Effect.UNKNOWN);
        assertThat(json.readTree(descriptor.schema()).path("required").get(0).asString()).isEqualTo("players");
        assertThat(registry.advertised("future.tool", Map.of())).isFalse();
        assertDenied(() -> registry.getToolCallbacks()[0].call("{}"), "tool_disabled");
        verify(future, never()).call(anyString());
        assertThat(registry.preset("all").get("future.tool")).isEqualTo(ToolPolicy.Access.DISABLED);
    }

    @Test void invalidDuplicateAndOversizedDefinitionsFailAtConstruction() {
        ToolCallback valid = callback("test.read", SCHEMA);
        assertThatThrownBy(() -> registry(new ToolCallback[]{valid, valid}, new Owners()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
        for (String id : List.of("<script>", "with space", "x".repeat(129))) {
            assertThatThrownBy(() -> registry(new ToolCallback[]{callback(id, SCHEMA)}, new Owners()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String schema : List.of("[]", "null", "{\"type\":\"string\"}", " ".repeat(65_537))) {
            assertThatThrownBy(() -> registry(new ToolCallback[]{callback("test.read", schema)}, new Owners()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        ToolCallback nullSchema = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(nullSchema.getToolDefinition()).thenReturn(definition);
        when(definition.name()).thenReturn("test.read");
        assertThatThrownBy(() -> registry(new ToolCallback[]{nullSchema}, new Owners()))
                .isInstanceOf(IllegalArgumentException.class);
        var hugeDescription = callback("test.read", SCHEMA);
        when(hugeDescription.getToolDefinition()).thenReturn(new DefaultToolDefinition("test.read", "x".repeat(16_385), SCHEMA));
        assertThatThrownBy(() -> registry(new ToolCallback[]{hugeDescription}, new Owners()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("description");
        ToolCallback[] tooMany = new ToolCallback[257];
        assertThatThrownBy(() -> registry(tooMany, new Owners())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void duplicateMetadataOwnersFailEvenWhenCallbackIdsAreUnique() {
        var delegate = callback("test.read", SCHEMA);
        assertThatThrownBy(() -> registry(new ToolCallback[]{delegate}, new Owners(), new DuplicateOwner()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Duplicate tool metadata ID.");
        verify(delegate, never()).call(anyString());
    }

    @Test void callbackAndContextOverloadsBothEnforcePolicyImmediatelyBeforeDelegate() {
        ToolCallback delegate = callback("test.read", SCHEMA);
        ToolRegistry registry = registry(new ToolCallback[]{delegate}, new Owners());
        ToolCallback wrapped = registry.getToolCallbacks()[0];
        ToolContext context = new ToolContext(Map.of("exchange", "native-context"));
        when(delegate.call("{}", context)).thenReturn("with-context");
        assertThat(wrapped.call("{}")).isEqualTo("result");
        assertThat(wrapped.call("{}", context)).isEqualTo("with-context");
        registry.setGlobal("test.read", ToolPolicy.Access.DISABLED);
        assertDenied(() -> wrapped.call("{}"), "tool_disabled");
        assertDenied(() -> wrapped.call("{}", context), "tool_disabled");
        verify(delegate, times(1)).call("{}");
        verify(delegate, times(1)).call("{}", context);
        assertThat(activity.history(Set.of(), true)).hasSize(4)
                .allSatisfy(e -> { assertThat(e.conversationId()).isNull(); assertThat(e.responseId()).isNull(); });
        assertThat(activity.history(Set.of(), true)).extracting(ToolActivity.Event::status)
                .containsExactly(ToolActivity.Status.DENIED, ToolActivity.Status.DENIED,
                        ToolActivity.Status.SUCCEEDED, ToolActivity.Status.SUCCEEDED);
    }

    @Test void askAndUnavailableCallsNeverInvokeHandlerAndUnknownIdsAreDeterministic() {
        ToolCallback delegate = callback("test.export", SCHEMA);
        ToolRegistry registry = registry(new ToolCallback[]{delegate}, new Owners());
        registry.setGlobal("test.export", ToolPolicy.Access.ASK);
        assertDenied(() -> registry.getToolCallbacks()[0].call("{}"), "approval_required");
        registry.setGlobal("test.export", ToolPolicy.Access.ALLOW);
        when(availability.unavailable(any())).thenReturn("Needs game data: load FM27 data first.");
        assertThat(registry.advertised("test.export", Map.of())).isFalse();
        assertDenied(() -> registry.getToolCallbacks()[0].call("{}"), "tool_unavailable");
        when(availability.unavailable(any())).thenThrow(new IllegalStateException("secret C:\\private"));
        assertDenied(() -> registry.getToolCallbacks()[0].call("{}"), "tool_unavailable");
        assertDenied(() -> registry.invoke("missing", "{}", null), "unknown_tool");
        verify(delegate, never()).call(anyString());
        assertThat(activity.history(Set.of(), true)).allSatisfy(e ->
                assertThat(e.summary()).doesNotContain("secret", "C:\\private"));
    }

    @Test void conversationSelectionsAreReevaluatedAndCannotOverrideLaterGlobalDeny() {
        ToolCallback delegate = callback("test.read", SCHEMA);
        ToolRegistry registry = registry(new ToolCallback[]{delegate}, new Owners());
        var selected = new AtomicReference<Map<String, ToolPolicy.Access>>(Map.of("test.read", ToolPolicy.Access.DISABLED));
        var invocation = new ToolRegistry.Invocation("chat-a", "response-a", selected::get);
        assertDenied(() -> registry.invoke("test.read", "{}", invocation), "tool_disabled");
        selected.set(Map.of("test.read", ToolPolicy.Access.ALLOW));
        assertThat(registry.invoke("test.read", "{}", invocation)).isEqualTo("result");
        registry.setGlobal("test.read", ToolPolicy.Access.DISABLED);
        assertDenied(() -> registry.invoke("test.read", "{}", invocation), "tool_disabled");
        assertThat(activity.history(Set.of("chat-a"), false)).allSatisfy(e -> {
            assertThat(e.conversationId()).isEqualTo("chat-a");
            assertThat(e.responseId()).isEqualTo("response-a");
            assertThat(e.source()).isEqualTo("OpenRouter");
        });
        assertThat(activity.history(Set.of("chat-b"), true)).isEmpty();
        verify(delegate, times(1)).call(anyString());
    }

    @Test void presetsUseMetadataAndResetDoesNotBypassGlobalRestrictions() {
        var registry = registry(new ToolCallback[]{callback("test.read", SCHEMA), callback("test.export", SCHEMA),
                callback("test.staff", SCHEMA), callback("test.tactics", SCHEMA)}, new Owners());
        assertThat(registry.preset("scouting")).containsEntry("test.read", ToolPolicy.Access.ALLOW)
                .containsEntry("test.staff", ToolPolicy.Access.DISABLED);
        assertThat(registry.preset("tactics")).containsEntry("test.tactics", ToolPolicy.Access.ALLOW)
                .containsEntry("test.read", ToolPolicy.Access.DISABLED);
        assertThat(registry.preset("staff")).containsEntry("test.staff", ToolPolicy.Access.ALLOW);
        policy.saveCustom("Chosen", Set.of("test.read", "removed-tool"));
        assertThat(registry.preset("custom")).containsEntry("test.read", ToolPolicy.Access.ALLOW)
                .containsEntry("test.export", ToolPolicy.Access.DISABLED).doesNotContainKey("removed-tool");
        registry.setGlobal("test.read", ToolPolicy.Access.DISABLED);
        assertThat(registry.effective("test.read", registry.preset("all"))).isEqualTo(ToolPolicy.Access.DISABLED);
        policy.replaceGlobal(Map.of());
        assertThat(registry.effective("test.read", registry.preset("all"))).isEqualTo(ToolPolicy.Access.ALLOW);
        assertThatThrownBy(() -> registry.preset("invented")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.setGlobal("test.read", ToolPolicy.Access.ASK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void anAlreadyRunningHandlerMayFinishButSubsequentCallsAreDenied() throws Exception {
        ToolCallback delegate = callback("test.read", SCHEMA);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(delegate.call(anyString())).thenAnswer(invocation -> {
            entered.countDown();
            if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
            return "finished";
        });
        ToolRegistry registry = registry(new ToolCallback[]{delegate}, new Owners());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(() -> registry.getToolCallbacks()[0].call("{}"));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                registry.setGlobal("test.read", ToolPolicy.Access.DISABLED);
                assertDenied(() -> registry.getToolCallbacks()[0].call("{}"), "tool_disabled");
            } finally { finish.countDown(); }
            assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo("finished");
        }
        verify(delegate, times(1)).call(anyString());
    }

    @Test void failuresAndActivityNeverExposeArgumentsResultsOrHandlerExceptionText() {
        var delegate = callback("test.read", SCHEMA);
        when(delegate.call(anyString())).thenThrow(new IllegalStateException("secret-key C:\\private\\players.json"));
        var registry = registry(new ToolCallback[]{delegate}, new Owners());
        assertThatThrownBy(() -> registry.getToolCallbacks()[0].call("{\"private-player\":\"secret-key\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Local tool execution failed. Check application data before starting a new chat.")
                .hasNoCause();
        var failed = activity.history(Set.of(), true).getFirst();
        assertThat(failed.status()).isEqualTo(ToolActivity.Status.FAILED);
        assertThat(failed.durationMillis()).isNotNegative();
        assertThat(failed.toString()).doesNotContain("secret-key", "private-player", "C:\\private", "players.json");
    }

    @Test void actualMethodCallbackValidationErrorsStayDistinctFromUnexpectedHandlerFailures() {
        Object[] owners = {new ErrorOwners()};
        var registered = MethodToolCallbackProvider.builder().toolObjects(owners).build().getToolCallbacks();
        var registry = registry(registered, owners);
        assertThatThrownBy(() -> registry.invoke("test.invalid", "{}", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid tool arguments. Check the tool schema and loaded application data.")
                .hasNoCause();
        assertThatThrownBy(() -> registry.invoke("test.failure", "{}", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Local tool execution failed. Check application data before starting a new chat.")
                .hasNoCause();
        assertThat(activity.history(Set.of(), true)).hasSize(2).allSatisfy(event -> {
            assertThat(event.status()).isEqualTo(ToolActivity.Status.FAILED);
            assertThat(event.durationMillis()).isNotNegative();
            assertThat(event.summary()).doesNotContain("secret", "private", "C:\\");
        });
    }

    @Test void cancellationAndAlreadyInterruptedCallsAreRecordedAsCancelled() {
        var delegate = callback("test.read", SCHEMA);
        when(delegate.call(anyString())).thenThrow(new java.util.concurrent.CancellationException("secret C:\\private"));
        var registry = registry(new ToolCallback[]{delegate}, new Owners());
        assertThatThrownBy(() -> registry.getToolCallbacks()[0].call("{}"))
                .isInstanceOf(IllegalStateException.class).hasNoCause();
        verify(delegate, times(1)).call(anyString());
        clearInvocations(delegate);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> registry.getToolCallbacks()[0].call("{}"))
                    .isInstanceOf(IllegalStateException.class).hasNoCause();
        } finally { Thread.interrupted(); }
        verify(delegate, never()).call(anyString());
        assertThat(activity.history(Set.of(), true)).hasSize(2).allSatisfy(event -> {
            assertThat(event.status()).isEqualTo(ToolActivity.Status.CANCELLED);
            assertThat(event.durationMillis()).isNotNegative();
            assertThat(event.summary()).doesNotContain("secret", "C:\\private");
        });
    }

    @Test void boundedHistoryUsesEventIdsAndClearingDoesNotResurrectRunningCalls() throws Exception {
        var observed = new ArrayList<ToolActivity.Event>();
        try (var observer = activity.subscribe(observed::add);
             var detached = activity.subscribe(event -> { throw new IllegalStateException("Detached UI"); })) {
            var running = activity.start("same-name", ToolInfo.Category.SCOUTING, "chat-a", "turn-1");
            var second = activity.start("same-name", ToolInfo.Category.SCOUTING, "chat-a", "turn-1");
            activity.finish(running, ToolActivity.Status.SUCCEEDED, "Completed");
            assertThat(activity.history(Set.of("chat-a"), false)).hasSize(2);
            assertThat(running.id()).isNotEqualTo(second.id());
            activity.clear(Set.of("chat-a"), false);
            activity.finish(second, ToolActivity.Status.SUCCEEDED, "Completed");
            assertThat(activity.history(Set.of("chat-a"), false)).isEmpty();
            assertThat(observed).hasSize(3);
        }
        for (int i = 0; i < 250; i++) activity.start("tool", ToolInfo.Category.OTHER, "chat-a", null);
        assertThat(activity.history(Set.of("chat-a"), false)).hasSize(200);
        for (int i = 0; i < 400; i++) activity.start("tool", ToolInfo.Category.OTHER, null, null);
        assertThat(activity.history(Set.of("chat-a"), true)).hasSize(500);
        assertThat(activity.history(Set.of("chat-b"), false)).isEmpty();
        activity.clear(Set.of("chat-b"), false);
        assertThat(activity.history(Set.of(), true)).hasSize(400);
        activity.clear(Set.of(), true);
        assertThat(activity.history(Set.of("chat-a"), true)).hasSize(100);
    }

    @Test void callbackArrayCannotMutateRegistry() {
        var registry = registry(new ToolCallback[]{callback("test.read", SCHEMA)}, new Owners());
        registry.getToolCallbacks()[0] = null;
        assertThat(registry.getToolCallbacks()[0]).isNotNull();
        assertThatThrownBy(() -> registry.descriptors().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private ToolRegistry registry(ToolCallback[] callbacks, Object... owners) {
        return new ToolRegistry(callbacks, owners, json, policy, availability, activity);
    }
    private static ToolCallback callback(String id, String schema) {
        var definition = new DefaultToolDefinition(id, "Description", schema);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(anyString())).thenReturn("result");
        return callback;
    }
    private static void assertDenied(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ToolRegistry.Denied.class,
                denied -> assertThat(denied.code()).isEqualTo(code));
    }
    public static class Owners {
        @Tool(name = "test.read", description = "Read") @ToolInfo(category = ToolInfo.Category.SCOUTING)
        public String read() { return "read"; }
        @Tool(name = "test.export", description = "Export")
        @ToolInfo(category = ToolInfo.Category.EXPORT, effect = ToolInfo.Effect.LOCAL_FILE_EXPORT)
        public String export() { return "export"; }
        @Tool(name = "test.staff", description = "Staff") @ToolInfo(category = ToolInfo.Category.STAFF)
        public String staff() { return "staff"; }
        @Tool(name = "test.tactics", description = "Tactics") @ToolInfo(category = ToolInfo.Category.TACTICS)
        public String tactics() { return "tactics"; }
    }
    public static class DuplicateOwner {
        @Tool(name = "test.read", description = "Duplicate") @ToolInfo(category = ToolInfo.Category.STAFF)
        public String read() { return "duplicate"; }
    }
    public static class ErrorOwners {
        @Tool(name = "test.invalid", description = "Validation failure") @ToolInfo(category = ToolInfo.Category.OTHER, snapshot = false)
        public String invalid() { throw new IllegalArgumentException("secret C:\\private\\argument.json"); }
        @Tool(name = "test.failure", description = "Unexpected failure") @ToolInfo(category = ToolInfo.Category.OTHER, snapshot = false)
        public String failure() { throw new IllegalStateException("secret C:\\private\\failure.json"); }
    }
}
