package com.github.fmaiassistent.copilot;

import java.util.List;

public record CopilotModel(String id, String name, List<String> reasoningEfforts, String defaultReasoningEffort) {
}
