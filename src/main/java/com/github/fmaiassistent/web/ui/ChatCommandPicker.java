package com.github.fmaiassistent.web.ui;

import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.Shortcuts;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextArea;

import java.util.List;

/** Shared command discovery and expansion for all embedded chat agents. */
final class ChatCommandPicker {
    private final TextArea input;
    private final Button menuButton = new Button("Commands");
    private final Div suggestions = new Div();
    private final Div inputGroup;
    private List<ChatCommands.Command> visible = List.of();
    private int highlighted;
    private boolean menuOpen;

    ChatCommandPicker(TextArea input, Runnable sendMessage) {
        this.input = input;
        menuButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        menuButton.addClassName("chat-commands-toggle");
        menuButton.getElement().setAttribute("aria-label", "Show chat commands");
        menuButton.getElement().setAttribute("aria-expanded", "false");
        menuButton.addClickListener(event -> {
            menuOpen = !menuOpen;
            render(menuOpen ? ChatCommands.ALL : ChatCommands.matches(input.getValue()));
            input.focus();
        });
        suggestions.addClassName("chat-command-suggestions");
        suggestions.getElement().setAttribute("aria-label", "Chat commands");
        suggestions.setVisible(false);
        inputGroup = new Div(suggestions, input);
        inputGroup.addClassName("chat-command-input-group");
        input.addValueChangeListener(event -> {
            if (event.isFromClient()) {
                menuOpen = false;
                render(ChatCommands.matches(event.getValue()));
            }
        });
        Shortcuts.addShortcutListener(input, () -> onEnter(sendMessage), Key.ENTER).listenOn(input);
        Shortcuts.addShortcutListener(input, () -> move(1), Key.ARROW_DOWN).listenOn(input);
        Shortcuts.addShortcutListener(input, () -> move(-1), Key.ARROW_UP).listenOn(input);
        Shortcuts.addShortcutListener(input, this::close, Key.ESCAPE).listenOn(input);
    }

    Div inputGroup() {
        return inputGroup;
    }

    Button menuButton() {
        return menuButton;
    }

    boolean expandBeforeSend() {
        return ChatCommands.expandKnown(input.getValue()).map(expanded -> {
            input.setValue(expanded);
            close();
            input.focus();
            return true;
        }).orElse(false);
    }

    void onEnter(Runnable sendMessage) {
        if (suggestions.isVisible() && !visible.isEmpty()) {
            select(visible.get(highlighted));
        } else if (!expandBeforeSend()) {
            sendMessage.run();
        }
    }

    void move(int step) {
        if (suggestions.isVisible() && !visible.isEmpty()) {
            highlighted = (highlighted + step + visible.size()) % visible.size();
            renderVisible();
        }
    }

    private void select(ChatCommands.Command command) {
        String current = input.getValue();
        String trailing = "";
        if (current != null && current.startsWith("/")) {
            int end = 1;
            while (end < current.length() && !Character.isWhitespace(current.charAt(end))) {
                end++;
            }
            trailing = current.substring(end);
        }
        input.setValue(command.expand(trailing));
        close();
        input.focus();
    }

    private void render(List<ChatCommands.Command> commands) {
        visible = commands;
        highlighted = 0;
        menuButton.getElement().setAttribute("aria-expanded", Boolean.toString(menuOpen));
        renderVisible();
    }

    private void renderVisible() {
        suggestions.removeAll();
        suggestions.setVisible(!visible.isEmpty());
        for (int i = 0; i < visible.size(); i++) {
            ChatCommands.Command command = visible.get(i);
            Button option = new Button();
            option.addClassName("chat-command-option");
            if (i == highlighted) {
                option.addClassName("chat-command-highlighted");
            }
            Span name = new Span(command.name());
            name.addClassName("chat-command-name");
            Span description = new Span(command.description());
            description.addClassName("chat-command-description");
            option.getElement().appendChild(name.getElement(), description.getElement());
            option.addClickListener(event -> select(command));
            suggestions.add(option);
        }
    }

    private void close() {
        menuOpen = false;
        visible = List.of();
        suggestions.removeAll();
        suggestions.setVisible(false);
        menuButton.getElement().setAttribute("aria-expanded", "false");
    }
}
