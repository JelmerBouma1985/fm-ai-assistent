package com.github.fmaiassistent.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void findsNearestProjectMarkerWithinBoundedParentWalk() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.createFile(root.resolve("pom.xml"));
        Path nested = Files.createDirectories(root.resolve("a/b/c"));

        assertThat(WorkspaceResolver.findProjectRoot(nested)).isEqualTo(root);
    }

    @Test
    void normalizesAbsoluteConfiguredDirectory() {
        Path configured = temporaryDirectory.resolve("first/../second");

        assertThat(WorkspaceResolver.resolve(configured.toString())).isEqualTo(configured.normalize());
    }
}
