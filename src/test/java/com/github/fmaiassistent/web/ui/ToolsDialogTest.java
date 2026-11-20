package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.ai.AiPromptContext;
import com.github.fmaiassistent.openrouter.OpenRouterClient;
import com.github.fmaiassistent.openrouter.OpenRouterConversationService;
import com.github.fmaiassistent.service.AppSettingsService;
import com.github.fmaiassistent.tools.*;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ToolsDialogTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final Owners owners = new Owners();
    private ToolRegistry registry;
    private ToolAvailability availability;
    private OpenRouterConversationService conversations;
    private String chat;
    private UI ui;

    @BeforeEach void setup() {
        ui = new UI(); // Vaadin's thread-local current instance is weakly held.
        UI.setCurrent(ui);
        availability = mock(ToolAvailability.class);
        when(availability.unavailable(any())).thenReturn("");
        var callbacks = MethodToolCallbackProvider.builder().toolObjects(owners).build().getToolCallbacks();
        registry = new ToolRegistry(callbacks, new Object[]{owners}, json,
                new ToolPolicy(mock(AppSettingsService.class), json), availability, new ToolActivity());
        conversations = new OpenRouterConversationService(json, mock(OpenRouterClient.class), registry,
                AiPromptContext.none(), "Test instructions");
        conversations.connect("test-session-key");
        chat = conversations.newConversation();
    }

    @AfterEach void close() {
        conversations.close();
        UI.setCurrent(null);
    }

    @Test void conversationAndGlobalTogglesComposeAndReopeningKeepsSelection() throws Exception {
        var dialog = dialog(true);
        Checkbox players = checkbox(dialog, "Find players");
        players.setValue(false);
        assertThat(conversations.toolSelection(chat)).containsEntry("fm27_find_players", ToolPolicy.Access.DISABLED);
        assertThat(registry.policy().globalOverrides()).isEmpty();
        scope(dialog).setValue("Global MCP settings");
        assertThat(checkbox(dialog, "Find players").getValue()).isTrue();
        checkbox(dialog, "Find staff").setValue(false);
        assertThat(registry.policy().globalOverrides()).containsEntry("fm27_find_staff", ToolPolicy.Access.DISABLED);
        scope(dialog).setValue("This conversation");
        assertThat(checkbox(dialog, "Find staff").getValue()).isTrue();
        assertThat(text(field(dialog, "rows"))).contains("Disabled by user policy");
        dialog.open(); dialog.close(); dialog.open(); dialog.close();
        var reopened = dialog(true);
        assertThat(checkbox(reopened, "Find players").getValue()).isFalse();
        button(reopened, "Reset to defaults").click();
        assertThat(conversations.toolSelection(chat)).isEmpty();
        assertThat(registry.effective("fm27_find_staff", conversations.toolSelection(chat))).isEqualTo(ToolPolicy.Access.DISABLED);
        assertThat(owners.calls).hasValue(0);
    }

    @Test void searchFiltersCategoryControlsAndStatusAreCheap() throws Exception {
        var dialog = dialog(true);
        TextField search = field(dialog, "search");
        search.setValue("players");
        assertThat(descendants(field(dialog, "rows"), Checkbox.class)).hasSize(1);
        button(dialog, "Disable category").click();
        assertThat(conversations.toolSelection(chat)).containsEntry("fm27_find_players", ToolPolicy.Access.DISABLED);
        select(dialog, "filter").setValue("Disabled");
        assertThat(checkbox(dialog, "Find players").getValue()).isFalse();
        select(dialog, "filter").setValue("Enabled");
        assertThat(text(field(dialog, "rows"))).contains("No tools match");
        select(dialog, "filter").setValue("All");
        button(dialog, "Enable category").click();
        assertThat(conversations.toolSelection(chat)).containsEntry("fm27_find_players", ToolPolicy.Access.ALLOW);
        when(availability.unavailable(any())).thenReturn("Needs game data: load FM27 data first.");
        search.setValue("");
        select(dialog, "filter").setValue("Unavailable");
        assertThat(text(field(dialog, "rows"))).contains("Needs game data");
        assertThat(owners.calls).hasValue(0);
    }

    @Test void presetsCustomNamesAndBulkResetOperateOnSelectedScope() throws Exception {
        var dialog = dialog(true);
        @SuppressWarnings("unchecked") Select<String> preset = (Select<String>) descendants(dialog, Select.class)
                .filter(select -> "Preset".equals(select.getLabel())).findFirst().orElseThrow();
        preset.setValue("scouting"); button(dialog, "Apply preset").click();
        assertThat(conversations.toolSelection(chat)).containsEntry("fm27_find_players", ToolPolicy.Access.ALLOW)
                .containsEntry("fm27_find_staff", ToolPolicy.Access.DISABLED);
        var name = descendants(dialog, TextField.class).filter(field -> "Custom preset name".equals(field.getLabel())).findFirst().orElseThrow();
        name.setValue("My scouting tools"); button(dialog, "Save current selection as custom").click();
        assertThat(registry.policy().customName()).isEqualTo("My scouting tools");
        assertThat(registry.policy().custom()).containsExactly("fm27_find_players");
        preset.setValue("staff"); button(dialog, "Apply preset").click();
        assertThat(conversations.toolSelection(chat)).containsEntry("fm27_find_staff", ToolPolicy.Access.ALLOW)
                .containsEntry("fm27_find_players", ToolPolicy.Access.DISABLED);
        preset.setValue("custom"); button(dialog, "Apply preset").click();
        assertThat(conversations.toolSelection(chat)).containsEntry("fm27_find_players", ToolPolicy.Access.ALLOW)
                .containsEntry("fm27_find_staff", ToolPolicy.Access.DISABLED);
        name.setValue(" "); button(dialog, "Save current selection as custom").click();
        assertThat(registry.policy().customName()).isEqualTo("My scouting tools");
        name.setValue("My scouting tools");
        scope(dialog).setValue("Global MCP settings");
        preset.setValue("tactics"); button(dialog, "Apply preset").click();
        assertThat(registry.policy().globalOverrides()).containsEntry("fm27_optimize_lineup", ToolPolicy.Access.ALLOW);
        button(dialog, "Reset to defaults").click();
        assertThat(registry.policy().globalOverrides()).isEmpty();
    }

    @Test void remoteGlobalMutationIsDeniedEvenFromProgrammaticEvents() throws Exception {
        var dialog = new ToolsDialog(registry, conversations, null, () -> false);
        Checkbox players = checkbox(dialog, "Find players");
        assertThat(players.isEnabled()).isFalse();
        players.setValue(false);
        button(dialog, "Disable category").click();
        button(dialog, "Apply preset").click();
        button(dialog, "Reset to defaults").click();
        assertThat(registry.policy().globalOverrides()).isEmpty();
        assertThat(text(field(dialog, "notice"))).contains("loopback");
        assertThat(owners.calls).hasValue(0);
    }

    @Test void localEffectsExposeAskAndNeverPromptOnReadOnlyQueries() throws Exception {
        var dialog = dialog(true);
        @SuppressWarnings("unchecked") Select<ToolPolicy.Access> mode = (Select<ToolPolicy.Access>) descendants(dialog, Select.class)
                .filter(select -> "Access for Create shortlist file".equals(select.getLabel())).findFirst().orElseThrow();
        mode.setValue(ToolPolicy.Access.ASK);
        assertThat(conversations.toolSelection(chat)).containsEntry("fm27_create_shortlist_file", ToolPolicy.Access.ASK);
        assertThat(text(field(dialog, "rows"))).contains("Approval required", "Creates a local export");
        assertThat(descendants(dialog, Select.class).filter(select -> "Access for Find players".equals(select.getLabel()))).isEmpty();
        assertThatThrownBy(() -> registry.invoke("fm27_create_shortlist_file", "{}", invocation(chat)))
                .isInstanceOf(ToolRegistry.Denied.class).hasMessageContaining("approval_required");
        assertThat(owners.calls).hasValue(0);
    }

    @Test void detailsPreserveNestedRequiredSchemaAndRenderDescriptionsAsText() throws Exception {
        var dialog = dialog(true);
        button(dialog, "Details: Find players").click();
        UI.getCurrent().getInternals().setSession(mock(com.vaadin.flow.server.VaadinSession.class));
        UI.getCurrent().getInternals().getStateTree().runExecutionsBeforeClientResponse();
        Dialog details = descendants(UI.getCurrent(), Dialog.class).filter(value -> "Find players".equals(value.getHeaderTitle()))
                .findFirst().orElseThrow();
        assertThat(descendants(details, Details.class)).hasSize(1);
        Pre schema = descendants(details, Pre.class).findFirst().orElseThrow();
        assertThat(schema.getText()).isEqualTo(registry.descriptor("fm27_find_players").schema()).contains("required", "query", "term");
        assertThat(descendants(details, TextField.class).filter(TextField::isReadOnly).findFirst().orElseThrow().getValue())
                .isEqualTo("fm27_find_players");
        assertThat(descendants(details, Span.class).map(Span::getText)).contains("<img src=x onerror=alert(1)> Search players");
        assertThat(details.getElement().toString()).doesNotContain("<img src=\"x\"");
        details.close();
        assertThat(owners.calls).hasValue(0);
    }

    @Test void historyFiltersAndClearNeverIncludeOtherSessionsOrGuessExternalOwnership() throws Exception {
        String second = conversations.newConversation();
        emit("fm27_find_players", ToolInfo.Category.SCOUTING, chat, ToolActivity.Status.SUCCEEDED);
        emit("fm27_find_staff", ToolInfo.Category.STAFF, second, ToolActivity.Status.FAILED);
        emit("fm27_find_players", ToolInfo.Category.SCOUTING, "other-session", ToolActivity.Status.SUCCEEDED);
        emit("fm27_find_staff", ToolInfo.Category.STAFF, null, ToolActivity.Status.DENIED);
        var dialog = dialog(true);
        Grid<ToolActivity.Event> history = field(dialog, "history");
        assertThat(history.getListDataView().getItems()).hasSize(1).allMatch(event -> chat.equals(event.conversationId()));
        select(dialog, "historyScope").setValue("This session and external MCP");
        assertThat(history.getListDataView().getItems()).hasSize(3).noneMatch(event -> "other-session".equals(event.conversationId()));
        ToolsDialogTest.<ComboBox<ToolActivity.Status>>field(dialog, "status").setValue(ToolActivity.Status.DENIED);
        assertThat(history.getListDataView().getItems()).hasSize(1).allMatch(event -> event.conversationId() == null);
        ToolsDialogTest.<ComboBox<ToolActivity.Status>>field(dialog, "status").clear();
        ToolsDialogTest.<ComboBox<ToolInfo.Category>>field(dialog, "category").setValue(ToolInfo.Category.STAFF);
        assertThat(history.getListDataView().getItems()).hasSize(2);
        button(dialog, "Clear session history").click();
        assertThat(history.getListDataView().getItems()).isEmpty();
        assertThat(registry.activity().history(Set.of("other-session"), false)).hasSize(1);
        var remote = new ToolsDialog(registry, conversations, chat, () -> false);
        emit("fm27_find_staff", ToolInfo.Category.STAFF, null, ToolActivity.Status.SUCCEEDED);
        select(remote, "historyScope").setValue("This session and external MCP");
        assertThat(ToolsDialogTest.<Grid<ToolActivity.Event>>field(remote, "history").getListDataView().getItems()).isEmpty();
    }

    @Test void observedPanelUsesActualStatusesAndSwitchesConversationWithoutMixingCalls() {
        AtomicReference<String> selected = new AtomicReference<>(chat);
        var panel = new ToolActivityPanel(conversations, selected::get);
        assertThat(text(panel)).isEmpty();
        emit("fm27_find_players", ToolInfo.Category.SCOUTING, chat, ToolActivity.Status.SUCCEEDED);
        emit("fm27_find_staff", ToolInfo.Category.STAFF, "other-session", ToolActivity.Status.FAILED);
        panel.refresh();
        assertThat(text(panel)).contains("Find players", "SUCCEEDED", "ms").doesNotContain("Find staff", "FAILED");
        String second = conversations.newConversation();
        selected.set(second); panel.refresh();
        assertThat(text(panel)).isEmpty();
        emit("fm27_find_staff", ToolInfo.Category.STAFF, second, ToolActivity.Status.DENIED);
        panel.refresh();
        assertThat(text(panel)).contains("Find staff", "DENIED").doesNotContain("Find players");
    }

    @Test void backgroundRenderRetainsTrustedDisplayButMutationsRecheckTheCurrentRequest() throws Exception {
        AtomicBoolean requestTrusted = new AtomicBoolean(true);
        var dialog = new ToolsDialog(registry, conversations, null, requestTrusted::get);
        emit("fm27_find_staff", ToolInfo.Category.STAFF, null, ToolActivity.Status.SUCCEEDED);
        requestTrusted.set(false); // Background UI.access has no servlet request.
        invokeNoArguments(dialog, "render");
        assertThat(checkbox(dialog, "Find players").isEnabled()).isTrue();
        Grid<ToolActivity.Event> history = field(dialog, "history");
        assertThat(history.getListDataView().getItems()).hasSize(1);
        checkbox(dialog, "Find players").setValue(false);
        assertThat(registry.policy().globalOverrides()).isEmpty();
        assertThat(text(field(dialog, "notice"))).contains("loopback");
        button(dialog, "Clear session history").click();
        assertThat(registry.activity().history(Set.of(), true)).hasSize(1);
        requestTrusted.set(true);
        checkbox(dialog, "Find players").setValue(false);
        assertThat(registry.policy().globalOverrides()).containsEntry("fm27_find_players", ToolPolicy.Access.DISABLED);
    }

    @Test void disconnectedDialogRendersSafelyAndNeverFallsBackToAnotherConversation() throws Exception {
        var dialog = dialog(true);
        Checkbox staleCheckbox = checkbox(dialog, "Find players");
        Button staleDetails = button(dialog, "Details: Find players");
        conversations.disconnect();
        conversations.connect("replacement-session-key");
        String replacement = conversations.newConversation();
        emit("fm27_find_staff", ToolInfo.Category.STAFF, replacement, ToolActivity.Status.SUCCEEDED);
        assertThatCode(() -> invokeNoArguments(dialog, "render")).doesNotThrowAnyException();
        assertThat(text(field(dialog, "notice"))).contains("expired or disconnected");
        assertThat(descendants(field(dialog, "rows"), Checkbox.class)).isEmpty();
        assertThatCode(() -> staleCheckbox.setValue(false)).doesNotThrowAnyException();
        assertThatCode(staleDetails::click).doesNotThrowAnyException();
        button(dialog, "Apply preset").click();
        button(dialog, "Refresh activity").click();
        assertThat(conversations.toolSelection(replacement)).isEmpty();
        assertThat(registry.policy().globalOverrides()).isEmpty();
        Grid<ToolActivity.Event> history = field(dialog, "history");
        assertThat(history.getListDataView().getItems()).isEmpty();
        assertThat(text(field(dialog, "notice"))).contains("expired or disconnected");
    }

    @Test void independentTabsAndConcurrentSelectionUpdatesDoNotLoseChangesOrOverrideGlobalDeny() throws Exception {
        var firstTab = dialog(true);
        var secondTab = dialog(true);
        Checkbox firstPlayers = checkbox(firstTab, "Find players");
        Checkbox secondStaff = checkbox(secondTab, "Find staff");
        firstPlayers.setValue(false);
        secondStaff.setValue(false);
        assertThat(conversations.toolSelection(chat)).containsEntry("fm27_find_players", ToolPolicy.Access.DISABLED)
                .containsEntry("fm27_find_staff", ToolPolicy.Access.DISABLED);
        conversations.setToolSelection(chat, Map.of());
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var players = workers.submit(() -> {
                start.await();
                conversations.updateToolSelection(chat, selection -> {
                    selection.put("fm27_find_players", ToolPolicy.Access.DISABLED); return selection;
                });
                return null;
            });
            var staff = workers.submit(() -> {
                start.await();
                conversations.updateToolSelection(chat, selection -> {
                    selection.put("fm27_find_staff", ToolPolicy.Access.DISABLED); return selection;
                });
                return null;
            });
            start.countDown(); players.get(5, TimeUnit.SECONDS); staff.get(5, TimeUnit.SECONDS);
        }
        assertThat(conversations.toolSelection(chat)).containsEntry("fm27_find_players", ToolPolicy.Access.DISABLED)
                .containsEntry("fm27_find_staff", ToolPolicy.Access.DISABLED);
        registry.setGlobal("fm27_find_players", ToolPolicy.Access.DISABLED);
        firstPlayers.setValue(true); // A stale checkbox cannot grant globally denied access.
        assertThat(conversations.toolSelection(chat)).containsEntry("fm27_find_players", ToolPolicy.Access.ALLOW);
        assertThat(registry.effective("fm27_find_players", conversations.toolSelection(chat))).isEqualTo(ToolPolicy.Access.DISABLED);
        assertThatThrownBy(() -> registry.invoke("fm27_find_players", "{\"query\":{\"term\":\"test\"}}", invocation(chat)))
                .isInstanceOf(ToolRegistry.Denied.class).hasMessageContaining("tool_disabled");
        assertThat(owners.calls).hasValue(0);
    }

    @Test void repeatedOpenCloseAndDetachReleaseActivityAndPolicyObservers() throws Exception {
        var dialog = dialog(true);
        UI.getCurrent().add(dialog);
        List<?> activityObservers = field(registry.activity(), "listeners");
        List<?> policyObservers = field(registry.policy(), "listeners");
        for (int iteration = 0; iteration < 5; iteration++) {
            dialog.open();
            assertThat(activityObservers).hasSize(1);
            assertThat(policyObservers).hasSize(1);
            emit("fm27_find_staff", ToolInfo.Category.STAFF, chat, ToolActivity.Status.SUCCEEDED);
            dialog.close();
            assertThat(activityObservers).isEmpty();
            assertThat(policyObservers).isEmpty();
        }
        dialog.open();
        UI.getCurrent().remove(dialog);
        assertThat(activityObservers).isEmpty();
        assertThat(policyObservers).isEmpty();
        assertThatCode(() -> emit("fm27_find_staff", ToolInfo.Category.STAFF, chat, ToolActivity.Status.FAILED))
                .doesNotThrowAnyException();
        assertThatCode(() -> registry.setGlobal("fm27_find_staff", ToolPolicy.Access.DISABLED)).doesNotThrowAnyException();
    }

    @Test void openingDialogBeforeItsAutomaticAttachmentStillRegistersLiveObservers() throws Exception {
        var dialog = dialog(true);
        assertThat(dialog.getUI()).isEmpty();
        dialog.open();
        assertThat(ToolsDialogTest.<List<?>>field(registry.activity(), "listeners")).hasSize(1);
        assertThat(ToolsDialogTest.<List<?>>field(registry.policy(), "listeners")).hasSize(1);
        dialog.close();
        assertThat(ToolsDialogTest.<List<?>>field(registry.activity(), "listeners")).isEmpty();
        assertThat(ToolsDialogTest.<List<?>>field(registry.policy(), "listeners")).isEmpty();
    }

    private ToolsDialog dialog(boolean trusted) { return new ToolsDialog(registry, conversations, chat, () -> trusted); }
    private ToolRegistry.Invocation invocation(String id) { return new ToolRegistry.Invocation(id, "response", () -> conversations.toolSelection(id)); }
    private void emit(String tool, ToolInfo.Category category, String conversation, ToolActivity.Status status) {
        var event = registry.activity().start(tool, category, conversation, conversation == null ? null : "response");
        registry.activity().finish(event, status, "Safe test summary");
    }
    private static Checkbox checkbox(Component root, String label) {
        var checkbox = descendants(root, Checkbox.class).filter(box -> label.equals(box.getLabel())).findFirst().orElseThrow();
        activateTab(root, checkbox);
        return checkbox;
    }
    private static Button button(Component root, String label) {
        var button = descendants(root, Button.class).filter(value -> label.equals(value.getText())).findFirst().orElseThrow();
        activateTab(root, button);
        return button;
    }
    private static void activateTab(Component root, Component target) {
        descendants(root, TabSheet.class).forEach(tabs -> {
            for (int index = 0; index < tabs.getTabCount(); index++) {
                var tab = tabs.getTabAt(index);
                if (descendants(tabs.getComponent(tab), Component.class).anyMatch(component -> component == target)) {
                    tabs.setSelectedTab(tab);
                    break;
                }
            }
        });
    }
    private static Select<String> scope(ToolsDialog dialog) throws Exception { return field(dialog, "scope"); }
    private static Select<String> select(ToolsDialog dialog, String field) throws Exception { return field(dialog, field); }
    private static String text(Component root) {
        return descendants(root, Component.class).map(component -> component.getElement().getText()).reduce("", (a, b) -> a + b);
    }
    private static <T> Stream<T> descendants(Component root, Class<T> type) {
        // Vaadin attaches a selected TabSheet page only before the client response.
        // Use its public content API to exercise all actual pages in server-side tests.
        Stream<Component> children = root.getChildren();
        if (root instanceof TabSheet tabs) children = Stream.concat(children,
                IntStream.range(0, tabs.getTabCount()).mapToObj(index -> tabs.getComponent(tabs.getTabAt(index)))).distinct();
        return Stream.concat(Stream.of(root), children.flatMap(child -> descendants(child, Component.class)))
                .filter(type::isInstance).map(type::cast);
    }
    @SuppressWarnings("unchecked") private static <T> T field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return (T) field.get(owner);
    }
    private static void invokeNoArguments(Object owner, String name) throws Exception {
        var method = owner.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(owner);
    }
    public static class Owners {
        final AtomicInteger calls = new AtomicInteger();
        @Tool(name = "fm27_find_players", description = "<img src=x onerror=alert(1)> Search players")
        @ToolInfo(category = ToolInfo.Category.SCOUTING, snapshot = false)
        public String players(@ToolParam(description = "Required nested search") Query query) { calls.incrementAndGet(); return "{}"; }
        @Tool(name = "fm27_find_staff", description = "Search staff")
        @ToolInfo(category = ToolInfo.Category.STAFF, snapshot = false)
        public String staff() { calls.incrementAndGet(); return "{}"; }
        @Tool(name = "fm27_optimize_lineup", description = "Find a lineup")
        @ToolInfo(category = ToolInfo.Category.TACTICS, snapshot = false, tactic = true)
        public String lineup() { calls.incrementAndGet(); return "{}"; }
        @Tool(name = "fm27_create_shortlist_file", description = "Create an app export")
        @ToolInfo(category = ToolInfo.Category.EXPORT, effect = ToolInfo.Effect.LOCAL_FILE_EXPORT, snapshot = false)
        public String export() { calls.incrementAndGet(); return "{}"; }
    }
    public record Query(@ToolParam(description = "Player search term") String term) { }
}
