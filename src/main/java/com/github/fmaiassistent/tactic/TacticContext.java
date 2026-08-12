package com.github.fmaiassistent.tactic;

import java.util.List;

public record TacticContext(
        long version,
        String title,
        String source,
        String markdown,
        List<String> importedFiles,
        List<String> warnings) {

    public TacticContext {
        importedFiles = List.copyOf(importedFiles);
        warnings = List.copyOf(warnings);
    }

    public boolean active() {
        return markdown != null && !markdown.isBlank();
    }

    static TacticContext empty(long version) {
        return new TacticContext(version, "No tactic loaded", null, null, List.of(), List.of());
    }
}
