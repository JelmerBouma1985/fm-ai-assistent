package com.github.fmaiassistent.copilot;

import com.github.fmaiassistent.ai.WorkspaceResolver;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
class CopilotWorkspaceResolver {
    private final Path workingDirectory;

    CopilotWorkspaceResolver(CopilotProperties properties) {
        workingDirectory = WorkspaceResolver.resolve(properties.workingDirectory());
    }

    Path workingDirectory() {
        return workingDirectory;
    }

}
