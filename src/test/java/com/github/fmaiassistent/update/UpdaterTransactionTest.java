package com.github.fmaiassistent.update;

import com.github.fmaiassistent.update.helper.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UpdaterTransactionTest {
    @TempDir Path temp;
    Path home, install, stage, backup, tx;
    Properties p;
    String id, os;
    @BeforeEach void setup() throws Exception {
        home = Files.createDirectory(temp.resolve("home")).toRealPath();
        install = temp.resolve("Installed app with spaces").toAbsolutePath();
        id = UUID.randomUUID().toString(); os = InstalledApplicationInfo.currentOs();
        tx = Files.createDirectories(home.resolve(".fm-ai-assistent/updater/" + id));
        stage = install.resolveSibling(install.getFileName() + ".update-" + id);
        backup = install.resolveSibling(install.getFileName() + ".backup-" + id);
        image(install, "2.1.0", os); image(stage, "2.1.1", os);
        Files.writeString(home.resolve(".fm-ai-assistent/settings.txt"), "keep");
        p = new Properties();
        p.setProperty("id", id); p.setProperty("install", install.toString()); p.setProperty("staged", stage.toString()); p.setProperty("backup", backup.toString());
        p.setProperty("state", "PREPARED"); p.setProperty("oldVersion", "2.1.0"); p.setProperty("newVersion", "2.1.1");
        p.setProperty("os", os); p.setProperty("game", "FM27");
        save();
    }
    static void image(Path root, String version, String os) throws IOException {
        Files.createDirectories(root.resolve(os.equals("windows") ? "runtime" : "lib/runtime"));
        Files.writeString(root.resolve(os.equals("windows") ? "runtime/release" : "lib/runtime/release"), "runtime fixture");
        Files.createDirectories(root.resolve("updater/runtime/bin"));
        Path launcher = ImageFiles.launcher(root, os); Files.createDirectories(launcher.getParent());
        Files.writeString(launcher, "fixture, not an executable");
        Files.writeString(root.resolve("updater/helper.jar"), "fixture");
        Files.writeString(root.resolve("updater/runtime/bin/" + (os.equals("windows") ? "java.exe" : "java")), "fixture");
        Path cfg = root.resolve(os.equals("linux") ? "lib/app/FM AI Assistent.cfg" : "app/FM AI Assistent.cfg");
        Files.createDirectories(cfg.getParent()); Files.writeString(cfg, "java-options=-Djpackage.app-version=" + version + "\napp.classpath=$APPDIR/fixture.jar\n");
        Files.writeString(cfg.getParent().resolve("fixture.jar"), "fixture");
        Path jvm = root.resolve(os.equals("linux") ? "lib/runtime/lib/server/libjvm.so" : "runtime/bin/server/jvm.dll");
        Files.createDirectories(jvm.getParent()); Files.writeString(jvm, "fixture");
        Properties m = new Properties(); m.setProperty("applicationId", "fm-ai-assistent"); m.setProperty("distribution", "desktop");
        m.setProperty("version", version); m.setProperty("os", os); m.setProperty("arch", "x64"); m.setProperty("game", "FM27");
        ImageFiles.store(root.resolve("app-update.properties"), m);
    }
    void save() throws IOException { ImageFiles.store(tx.resolve("transaction.properties"), p); }
    InstallationTransaction transaction() throws IOException { return new InstallationTransaction(tx, home, 100); }
    @Test void noCleanShutdownNeverReplacesImage() throws Exception {
        assertThrows(IOException.class, () -> transaction().execute());
        assertEquals("2.1.0", ImageFiles.metadata(install).getProperty("version")); assertFalse(Files.exists(backup));
    }
    @Test void hungOrStalePidNeverKillsProcessOrReplacesImage() throws Exception {
        p.setProperty("oldPid", Long.toString(ProcessHandle.current().pid()));
        p.setProperty("oldStart", ProcessHandle.current().info().startInstant().orElseThrow().toString()); save();
        assertThrows(IOException.class, () -> transaction().execute());
        assertTrue(ProcessHandle.current().isAlive()); assertFalse(Files.exists(backup));
        p.setProperty("oldStart", "2000-01-01T00:00:00Z"); save();
        assertThrows(IOException.class, () -> transaction().execute());
        assertEquals("2.1.0", ImageFiles.metadata(install).getProperty("version"));
    }
    @Test void recoverInterruptedBetweenRenamesWithoutDeletingImages() throws Exception {
        ImageFiles.move(install, backup); p.setProperty("state", "BACKUP_PENDING"); save();
        transaction().recover();
        assertEquals("2.1.0", ImageFiles.metadata(install).getProperty("version"));
        assertTrue(Files.exists(stage)); assertFalse(Files.exists(backup));
        assertEquals("RECOVERED", ImageFiles.properties(tx.resolve("transaction.properties")).getProperty("state"));
        transaction().recover();
    }
    @Test void postLaunchRecoveryRestoresBinariesAndBlocksUnsafeDataRestart() throws Exception {
        ImageFiles.move(install, backup); ImageFiles.move(stage, install);
        p.setProperty("state", "AWAITING_HEALTH"); save();
        transaction().recover();
        assertEquals("2.1.0", ImageFiles.metadata(install).getProperty("version"));
        assertTrue(Files.exists(install.resolve(".updater-data-review")));
        assertEquals("keep", Files.readString(home.resolve(".fm-ai-assistent/settings.txt")));
        assertTrue(Files.exists(install.resolveSibling(install.getFileName() + ".failed-" + id)));
    }
    @Test void cannotInstallArbitraryPathsOrMutateHome() throws Exception {
        assertThrows(IOException.class, () -> ImageFiles.safeInstall(home, home));
        assertThrows(IOException.class, () -> ImageFiles.safeInstall(home.resolve(".fm-ai-assistent"), home));
        assertThrows(IOException.class, () -> ImageFiles.safeInstall(home.getRoot(), home));
        Path checkout = Files.createDirectory(temp.resolve("checkout")); Files.createDirectory(checkout.resolve(".git"));
        assertThrows(IOException.class, () -> ImageFiles.safeInstall(checkout, home));
        p.setProperty("staged", temp.resolve("unrelated").toString()); save();
        assertThrows(IOException.class, this::transaction);
    }
    @Test void backupAfterShutdownAndFailedLaunchLeaveDataSafe() throws Exception {
        Files.createFile(tx.resolve("clean-shutdown"));
        assertThrows(Exception.class, () -> transaction().execute()); // fixture launcher cannot launch
        assertEquals("keep", Files.readString(tx.resolve("data-backup/settings.txt")));
        assertEquals("keep", Files.readString(home.resolve(".fm-ai-assistent/settings.txt")));
        assertEquals("2.1.0", ImageFiles.metadata(install).getProperty("version"));
        assertTrue(Files.exists(install.resolve(".updater-data-review")));
    }
    @Test void realChildAcknowledgesHealthAndCommits() throws Exception {
        Files.createFile(tx.resolve("clean-shutdown"));
        var child = new java.util.concurrent.atomic.AtomicReference<Process>();
        try {
            var transaction = new InstallationTransaction(tx, home, 10000, (command, directory) -> {
                assertEquals(ImageFiles.launcher(install, os).toString(), command.getFirst());
                Process process = fixture("healthy"); child.set(process); return process;
            });
            transaction.execute();
            assertEquals("COMPLETED", ImageFiles.properties(tx.resolve("transaction.properties")).getProperty("state"));
            assertEquals("2.1.1", ImageFiles.metadata(install).getProperty("version"));
            assertEquals("2.1.0", ImageFiles.metadata(backup).getProperty("version"));
            assertEquals("keep", Files.readString(tx.resolve("data-backup/settings.txt")));
        } finally { if (child.get() != null) { child.get().destroyForcibly(); child.get().waitFor(); } }
    }
    @Test void realChildFailureRequiresRecoveryAndDoesNotRelaunchOldCode() throws Exception {
        Files.createFile(tx.resolve("clean-shutdown"));
        var transaction = new InstallationTransaction(tx, home, 10000, (command, directory) -> fixture("fail"));
        assertThrows(IOException.class, transaction::execute);
        assertEquals("DATA_REVIEW_REQUIRED", ImageFiles.properties(tx.resolve("transaction.properties")).getProperty("state"));
        transaction().recover();
        assertTrue(Files.exists(install.resolve(".updater-data-review")));
    }
    private Process fixture(String mode) throws IOException {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", os.equals("windows") ? "java.exe" : "java").toString(),
                "-cp", System.getProperty("java.class.path"), UpdaterProcessFixture.class.getName(), tx.toString(), mode).redirectErrorStream(true)
                .redirectOutput(tx.resolve("fixture.log").toFile()).start();
    }
}
