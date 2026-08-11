package com.github.fmaiassistent.antigravity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

interface AntigravityProcessLauncher {
    AntigravityManagedProcess launch(List<String> command, Path workingDirectory) throws IOException;
}
