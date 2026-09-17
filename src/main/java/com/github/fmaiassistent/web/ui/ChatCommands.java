package com.github.fmaiassistent.web.ui;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class ChatCommands {
    record Command(String name, String description, String question, String requirement) {
        String expand(String trailing) {
            String extra = trailing == null ? "" : trailing.strip();
            return question + (extra.isEmpty() ? "" : " " + requirement + ": " + extra);
        }
    }

    static final List<Command> ALL = List.of(
            new Command("/squad", "Review squad depth, weak positions and contract risks",
                    "Analyze my squad depth, weak positions, injuries and contract risks. If the relevant FM26 data or context is missing, tell me what to load.", "Focus on"),
            new Command("/lineup", "Best XI for your uploaded tactic · tactic required",
                    "Build the best starting XI for my uploaded FM26 tactic, explain the key choices and alternatives. If no tactic is uploaded or squad data is missing, tell me what to load.", "Requirements"),
            new Command("/recruit", "Find transfer targets for a position or role",
                    "Find realistic transfer targets for my club, considering squad needs, position fit and affordability. If club or player data is missing, tell me what to load.", "Requirements"),
            new Command("/compare", "Compare players and their fit for my club",
                    "Compare the players I name, including their fit, cost and risks. If you cannot identify them or the relevant data is missing, tell me what details to provide or load.", "Players and requirements"),
            new Command("/staff", "Find or assess staff and coaching roles",
                    "Help me find or assess staff for my club, including coaching role strengths where relevant. If staff data is missing, tell me what to load.", "Requirements"),
            new Command("/club", "Inspect a club, budget and squad context",
                    "Summarize the club I name, or my managed club if I name none, including its finances and squad context. If club data is missing, tell me what to load.", "Club and requirements")
    );

    private ChatCommands() { }

    static List<Command> matches(String text) {
        if (text == null || !text.startsWith("/")) {
            return List.of();
        }
        String token = text.split("\\s", 2)[0].toLowerCase(Locale.ROOT);
        return ALL.stream().filter(command -> command.name().startsWith(token)).toList();
    }

    static Optional<String> expandKnown(String text) {
        if (text == null || !text.startsWith("/")) {
            return Optional.empty();
        }
        int end = 1;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        String name = text.substring(0, end);
        String trailing = text.substring(end);
        return ALL.stream().filter(command -> command.name().equalsIgnoreCase(name))
                .findFirst().map(command -> command.expand(trailing));
    }
}
