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
                    "Use the fm26_analyze_squad tool now for my detected managed club and loaded FM26 data. Then analyze squad depth, weak positions, injuries and contract risks. Only if that tool reports that FM26 data is unavailable, tell me what to load.", "Focus on"),
            new Command("/lineup", "Best XI for your uploaded tactic · tactic required",
                    "Use the fm26_optimize_lineup tool now for my detected managed club and loaded FM26 tactic. Then build the best starting XI, explain the key choices and alternatives. Only if that tool reports that the tactic or FM26 data is unavailable, tell me what to load.", "Requirements"),
            new Command("/recruit", "Find transfer targets for a position or role",
                    "Use the fm26_transfer_shortlist tool now for my detected managed club and loaded FM26 data. That tool must load the current squad and player market data, then return realistic targets using squad needs, position fit and affordability. Only if that tool reports that club or FM26 data is unavailable, tell me what to load.", "Requirements"),
            new Command("/compare", "Compare players and their fit for my club",
                    "Compare the players I name, including their fit, cost and risks. If you cannot identify them or the relevant data is missing, tell me what details to provide or load.", "Players and requirements"),
            new Command("/staff", "Find or assess staff and coaching roles",
                    "Help me find or assess staff for my club, including coaching role strengths where relevant. If staff data is missing, tell me what to load.", "Requirements"),
            new Command("/club", "Inspect a club, budget and squad context",
                    "Use the fm26_get_club_context tool now for the named club, or my detected managed club if I name none. Then summarize its profile, finances and returned squad context. Only if that tool reports that club or FM26 data is unavailable, tell me what to load.", "Club and requirements")
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
