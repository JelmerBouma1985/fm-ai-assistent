package com.github.fmaiassistent.tools;

import com.github.fmaiassistent.domain.enums.MoneyCurrency;
import com.github.fmaiassistent.service.AppSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ResourceLock("SYSTEM_PROPERTIES")
class ToolPolicyTest {
    private final JsonMapper json = JsonMapper.builder().build();
    @TempDir Path temporary;

    @Test void firstRunPreservesKnownToolsAndConservativelyDisablesUnknownEffects() {
        var policy = policy(mock(AppSettingsService.class));
        for (var effect : Set.of(ToolInfo.Effect.READ_ONLY, ToolInfo.Effect.APP_LOCAL_CHANGE, ToolInfo.Effect.LOCAL_FILE_EXPORT))
            assertThat(policy.global("new.tool", effect)).isEqualTo(ToolPolicy.Access.ALLOW);
        assertThat(policy.global("new.tool", ToolInfo.Effect.UNKNOWN)).isEqualTo(ToolPolicy.Access.DISABLED);
        assertThat(policy.globalOverrides()).isEmpty();
        assertThat(policy.notice()).isEmpty();
    }

    @Test void restartRestoresAccessAndEditableCustomPresetWithoutAffectingOtherSettings() throws Exception {
        var settings = settings(temporary.resolve("settings.properties"));
        settings.saveCurrency(MoneyCurrency.EURO);
        var policy = policy(settings);
        policy.setGlobal("fm27_find_players", ToolPolicy.Access.DISABLED);
        policy.setGlobal("fm27_create_shortlist_file", ToolPolicy.Access.ASK);
        policy.saveCustom("  Recruitment choices  ", Set.of("fm27_find_players", "removed-tool"));
        var restarted = policy(settings(settings.settingsPath()));
        assertThat(restarted.globalOverrides()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "fm27_find_players", ToolPolicy.Access.DISABLED, "fm27_create_shortlist_file", ToolPolicy.Access.ASK));
        assertThat(restarted.customName()).isEqualTo("Recruitment choices");
        assertThat(restarted.custom()).containsExactlyInAnyOrder("fm27_find_players", "removed-tool");
        assertThat(settings(settings.settingsPath()).currency()).isEqualTo(MoneyCurrency.EURO);
        var stored = json.readTree(settings.toolPolicy());
        assertThat(stored.propertyNames()).containsExactlyInAnyOrder("version", "customName", "access", "custom");
        assertThat(Files.readString(settings.settingsPath())).doesNotContain("conversation", "apiKey", "arguments", "result", "prompt");
        restarted.replaceGlobal(Map.of());
        assertThat(policy(settings).globalOverrides()).isEmpty();
        assertThat(policy(settings).customName()).isEqualTo("Recruitment choices");
    }

    @Test void malformedAndFuturePreferencesUseDefaultsWithoutCrashing() {
        for (String content : new String[]{"not-json", "null", "[]", "{}", "{\"version\":2,\"access\":{}}",
                "{\"version\":1,\"access\":{\"tool\":\"invented\"}}",
                "{\"version\":1,\"access\":{\"<script>\":\"ALLOW\"}}", "x".repeat(65_537)}) {
            var settings = mock(AppSettingsService.class);
            when(settings.toolPolicy()).thenReturn(content);
            var policy = policy(settings);
            assertThat(policy.globalOverrides()).isEmpty();
            assertThat(policy.custom()).isEmpty();
            assertThat(policy.notice()).contains("could not be read");
            verify(settings, never()).saveToolPolicy(anyString(), anyBoolean());
        }
    }

    @Test void savingAfterCorruptPolicyKeepsRecoveryCopyAndPreservesCurrency() throws Exception {
        Path file = temporary.resolve("corrupt.properties");
        Properties properties = new Properties();
        properties.setProperty("currency", "euro");
        properties.setProperty("tools.policy", "{broken-json");
        try (OutputStream out = Files.newOutputStream(file)) { properties.store(out, "Fixture"); }
        byte[] original = Files.readAllBytes(file);
        var settings = settings(file);
        var policy = policy(settings);
        assertThat(policy.notice()).isNotEmpty();
        policy.setGlobal("read.tool", ToolPolicy.Access.DISABLED);
        assertThat(policy.notice()).isEmpty();
        try (var paths = Files.list(temporary)) {
            var copies = paths.filter(path -> path.getFileName().toString().contains(".recovery-")).toList();
            assertThat(copies).hasSize(1);
            assertThat(Files.readAllBytes(copies.getFirst())).isEqualTo(original);
        }
        assertThat(policy(settings(file)).global("read.tool", ToolInfo.Effect.READ_ONLY)).isEqualTo(ToolPolicy.Access.DISABLED);
        Properties restored = new Properties();
        try (var input = Files.newInputStream(file)) { restored.load(input); }
        assertThat(restored.getProperty("currency")).isEqualTo("euro");
    }

    @Test void failedSaveDoesNotPublishUnsavedGlobalOrCustomState() {
        var settings = mock(AppSettingsService.class);
        var policy = policy(settings);
        policy.setGlobal("existing", ToolPolicy.Access.ASK);
        policy.saveCustom("Existing", Set.of("existing"));
        doThrow(new IllegalStateException("Disk unavailable")).when(settings).saveToolPolicy(anyString(), anyBoolean());
        assertThatThrownBy(() -> policy.setGlobal("new", ToolPolicy.Access.DISABLED)).isInstanceOf(IllegalStateException.class);
        assertThat(policy.globalOverrides()).containsExactlyEntriesOf(Map.of("existing", ToolPolicy.Access.ASK));
        assertThatThrownBy(() -> policy.saveCustom("New", Set.of("new"))).isInstanceOf(IllegalStateException.class);
        assertThat(policy.customName()).isEqualTo("Existing");
        assertThat(policy.custom()).containsExactly("existing");
    }

    @Test void preferenceLimitsRejectInvalidIdsNamesAndOversizedSelectionsBeforeSaving() {
        var settings = mock(AppSettingsService.class);
        var policy = policy(settings);
        for (String id : new String[]{"", "tool name", "<img>", "x".repeat(129)})
            assertThatThrownBy(() -> policy.setGlobal(id, ToolPolicy.Access.ALLOW)).isInstanceOf(IllegalArgumentException.class);
        for (String name : new String[]{"", "  ", "a\nb", "x".repeat(61)})
            assertThatThrownBy(() -> policy.saveCustom(name, Set.of("tool"))).isInstanceOf(IllegalArgumentException.class);
        var oversized = new HashMap<String, ToolPolicy.Access>();
        for (int i = 0; i < 257; i++) oversized.put("tool-" + i, ToolPolicy.Access.ALLOW);
        assertThatThrownBy(() -> policy.replaceGlobal(oversized)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.saveCustom("Custom", oversized.keySet())).isInstanceOf(IllegalArgumentException.class);
        verify(settings, never()).saveToolPolicy(anyString(), anyBoolean());
    }

    @Test void concurrentUpdatesDoNotLoseIndependentTogglesOrPublishMutableCollections() throws Exception {
        var policy = policy(mock(AppSettingsService.class));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 100).mapToObj(i -> executor.submit(() ->
                    policy.setGlobal("tool-" + i, ToolPolicy.Access.DISABLED))).toList();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertThat(policy.globalOverrides()).hasSize(100).allSatisfy((id, access) -> assertThat(access).isEqualTo(ToolPolicy.Access.DISABLED));
        var mutable = new HashMap<>(Map.of("tool", ToolPolicy.Access.ALLOW));
        policy.replaceGlobal(mutable);
        mutable.put("tool", ToolPolicy.Access.DISABLED);
        assertThat(policy.global("tool", ToolInfo.Effect.READ_ONLY)).isEqualTo(ToolPolicy.Access.ALLOW);
        assertThatThrownBy(() -> policy.globalOverrides().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void concurrentBulkUpdatesMergeIndependentCategoriesAgainstLatestGlobalState() throws Exception {
        var policy = policy(mock(AppSettingsService.class));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 100).mapToObj(i -> executor.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
                policy.updateGlobal(current -> {
                    current.put("category-" + i + "-first", ToolPolicy.Access.DISABLED);
                    current.put("category-" + i + "-second", ToolPolicy.Access.ASK);
                    return current;
                });
                return null;
            })).toList();
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertThat(policy.globalOverrides()).hasSize(200);
        for (int i = 0; i < 100; i++) assertThat(policy.globalOverrides())
                .containsEntry("category-" + i + "-first", ToolPolicy.Access.DISABLED)
                .containsEntry("category-" + i + "-second", ToolPolicy.Access.ASK);
    }

    @Test void detachedPolicyListenerCannotPreventPersistPublishOrOtherObservers() throws Exception {
        var settings = mock(AppSettingsService.class);
        var policy = policy(settings);
        var observed = new java.util.ArrayList<Map<String, ToolPolicy.Access>>();
        try (var detached = policy.subscribe(() -> { throw new IllegalStateException("Detached UI"); });
             var observer = policy.subscribe(() -> observed.add(policy.globalOverrides()))) {
            policy.setGlobal("tool", ToolPolicy.Access.DISABLED);
            policy.saveCustom("Selected", Set.of("tool"));
            assertThat(observed).hasSize(2).allSatisfy(state ->
                    assertThat(state).containsExactlyEntriesOf(Map.of("tool", ToolPolicy.Access.DISABLED)));
            assertThat(policy.customName()).isEqualTo("Selected");
        }
        policy.setGlobal("other", ToolPolicy.Access.ALLOW);
        assertThat(observed).hasSize(2);
        verify(settings, times(3)).saveToolPolicy(anyString(), eq(false));
    }

    @Test void combinedCustomAndGlobalPreferencesCannotExceedPersistedSizeCap() {
        var settings = mock(AppSettingsService.class);
        var policy = policy(settings);
        var longIds = java.util.stream.IntStream.range(0, 256)
                .mapToObj(i -> "x".repeat(124) + String.format("%04d", i)).collect(java.util.stream.Collectors.toSet());
        policy.saveCustom("Large selection", longIds);
        clearInvocations(settings);
        var modes = new HashMap<String, ToolPolicy.Access>();
        longIds.forEach(id -> modes.put(id, ToolPolicy.Access.DISABLED));
        assertThatThrownBy(() -> policy.replaceGlobal(modes)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tool preferences exceed size limit.");
        assertThat(policy.globalOverrides()).isEmpty();
        assertThat(policy.custom()).containsExactlyInAnyOrderElementsOf(longIds);
        verify(settings, never()).saveToolPolicy(anyString(), anyBoolean());
    }

    @Test void oversizedSettingsAreBoundedAndReportedWithoutReadingAnUnboundedFile() throws Exception {
        Path file = temporary.resolve("oversized.properties");
        Files.writeString(file, "x".repeat(512 * 1024 + 1));
        var policy = policy(settings(file));
        assertThat(policy.notice()).contains("could not be read");
        assertThat(policy.globalOverrides()).isEmpty();
    }

    private ToolPolicy policy(AppSettingsService settings) { return new ToolPolicy(settings, json); }
    private static AppSettingsService settings(Path path) {
        String previous = System.getProperty("fmaiassistent.settings.file");
        try {
            System.setProperty("fmaiassistent.settings.file", path.toString());
            return new AppSettingsService();
        } finally {
            if (previous == null) System.clearProperty("fmaiassistent.settings.file");
            else System.setProperty("fmaiassistent.settings.file", previous);
        }
    }
}
