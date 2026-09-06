package com.github.fmaiassistent.codex;

import com.github.fmaiassistent.ai.WorkspaceResolver;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
class CodexWorkspaceResolver {
    private final Path workingDirectory;

    CodexWorkspaceResolver(CodexProperties properties) {
        workingDirectory = WorkspaceResolver.resolve(properties.workingDirectory());
    }

    Path workingDirectory() {
        return workingDirectory;
    }

}
