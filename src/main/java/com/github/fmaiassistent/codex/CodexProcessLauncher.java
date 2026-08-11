package com.github.fmaiassistent.codex;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@FunctionalInterface
interface CodexProcessLauncher {
    CodexManagedProcess launch(List<String> command, Path workingDirectory) throws IOException;
}
