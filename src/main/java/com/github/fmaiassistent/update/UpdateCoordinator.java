package com.github.fmaiassistent.update;

import com.github.fmaiassistent.update.helper.ImageFiles;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static com.github.fmaiassistent.update.UpdateStatus.Phase.*;

/** One coordinator for all browser sessions, with one bounded worker and one installation at a time. */
@Service
public class UpdateCoordinator {
    private final UpdateProperties options;
    private final Environment environment;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("desktop-updates").unstarted(r));
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile UpdateStatus status = new UpdateStatus(NO_UPDATE, "Updates have not been checked", "unpackaged", null, 0, 0);
    private volatile Consumer<Path> shutdown;
    private InstalledApplicationInfo installed;
    private UpdateSource source;
    public UpdateCoordinator(UpdateProperties options, Environment environment) { this.options = options; this.environment = environment; }
    public UpdateStatus status() { return status; }
    public boolean local() { return options.getSource().equals("local"); }
    public boolean enabled() { return options.isEnabled(); }
    private Path home() { return Path.of(System.getProperty("user.home")).toAbsolutePath().normalize(); }
    private Path stateRoot() { return home().resolve(".fm-ai-assistent/updater"); }
    @EventListener(ApplicationReadyEvent.class)
    public void ready() { if (options.isEnabled() && options.isCheckOnStartup()) worker.schedule(() -> check(false), 3, TimeUnit.SECONDS); }
    public void desktopReady(Consumer<Path> shutdownAction) throws IOException {
        shutdown = shutdownAction;
        String id = environment.getProperty("app.updates.transaction");
        if (id == null) {
            try { installed = InstalledApplicationInfo.detect(); publish(NO_UPDATE, options.isEnabled() ? "Updates have not been checked" : "Update checks are disabled", null, 0, 0); }
            catch (IOException | IllegalArgumentException e) { publish(UNSUPPORTED, "Manual updates are available on GitHub Releases", null, 0, 0); }
            return;
        }
        if (!id.matches("[0-9a-f-]{36}")) throw new IOException("Invalid update transaction acknowledgement");
        installed = InstalledApplicationInfo.detect();
        Path dir = stateRoot().resolve(id).toRealPath();
        var tx = ImageFiles.properties(dir.resolve("transaction.properties"));
        if (!dir.getParent().equals(stateRoot().toRealPath()) || !id.equals(tx.getProperty("id"))
                || !installed.root().toString().equals(tx.getProperty("install")) || !installed.version().equals(tx.getProperty("newVersion"))) throw new IOException("Update acknowledgement does not match installation");
        Properties ack = new Properties(); ack.setProperty("id", id); ack.setProperty("version", installed.version());
        ImageFiles.store(dir.resolve("healthy.properties"), ack);
        publish(COMPLETED, "Updated application is ready", null, 0, 0);
    }
    public synchronized void check(boolean manual) {
        if (status.busy()) return;
        if (!options.isEnabled()) { publish(UNSUPPORTED, "Update checks are disabled", null, 0, 0); return; }
        publish(CHECKING, "Checking for updates", null, 0, 0);
        worker.execute(() -> {
            try {
                if (local() && (!options.isDeveloperMode() || options.getLocalDirectory().isBlank())) throw new IOException("Local updates require developer-mode=true and an existing local-directory");
                if (!local() && !options.getSource().equals("github")) throw new IOException("Unknown update source; choose github or local");
                try { installed = InstalledApplicationInfo.detect(); }
                catch (IOException | IllegalArgumentException e) { publish(UNSUPPORTED, "Automatic installation requires a writable Windows/Linux x64 desktop app-image. Use GitHub Releases for manual updates.", null, 0, 0); return; }
                if (source == null) source = local() ? new LocalDirectoryUpdateSource(Path.of(options.getLocalDirectory())) : new GitHubReleaseSource();
                var candidate = source.findBestCompatibleRelease(installed);
                if (candidate.isPresent() && (manual || !dismissed(candidate.get().version()))) publish(AVAILABLE, "A compatible update is available", candidate.get(), 0, 0);
                else publish(NO_UPDATE, candidate.isPresent() ? "Update postponed" : "No newer compatible update", null, 0, 0);
            } catch (Exception e) { error(e); }
        });
    }
    public synchronized void install() {
        if (status.phase() != AVAILABLE || shutdown == null) return;
        var release = status.release();
        var asset = ReleaseSelector.asset(release, installed).orElseThrow();
        cancelled.set(false);
        publish(DOWNLOADING, "Downloading update", release, 0, asset.sizeBytes());
        worker.execute(() -> {
            try {
                ImageFiles.safeInstall(installed.root(), home());
                String expectedDatabase = "jdbc:h2:file:" + home().toString().replace('\\', '/') + "/.fm-ai-assistent/fm-ai-assistent;DB_CLOSE_ON_EXIT=FALSE";
                String actualDatabase = environment.getProperty("spring.datasource.url", "").replace('\\', '/');
                if (!expectedDatabase.equals(actualDatabase)) throw new IOException("Automatic updates require the standard home-directory H2 database so a consistent backup can be guaranteed");
                String id = UUID.randomUUID().toString();
                Files.createDirectories(stateRoot());
                Path transaction = Files.createDirectory(stateRoot().resolve(id));
                Path archive = new UpdateDownloadService().download(source, asset, transaction, cancelled::get,
                        count -> publish(DOWNLOADING, "Downloading update", release, count, asset.sizeBytes()));
                publish(VERIFYING, "Archive checksum and size verified", release, asset.sizeBytes(), asset.sizeBytes());
                if (cancelled.get()) throw new IOException("Update cancelled");
                publish(PREPARING, "Validating and staging desktop image", release, 0, 0);
                Path stage = installed.root().resolveSibling(installed.root().getFileName() + ".update-" + id);
                Path extraction = installed.root().resolveSibling(installed.root().getFileName() + ".extract-" + id);
                Path extracted = new ArchiveStager().extract(archive, extraction, release, asset);
                ImageFiles.move(extracted, stage);
                Files.delete(extraction);
                // Full dedicated helper runtime, independent of both app-images and host JDK.
                ImageFiles.copyTree(installed.root().resolve("updater"), transaction.resolve("helper"));
                Properties tx = new Properties();
                tx.setProperty("id", id); tx.setProperty("state", "PREPARED");
                tx.setProperty("install", installed.root().toString()); tx.setProperty("staged", stage.toString());
                tx.setProperty("backup", installed.root().resolveSibling(installed.root().getFileName() + ".backup-" + id).toString());
                tx.setProperty("oldVersion", installed.version()); tx.setProperty("newVersion", release.version());
                tx.setProperty("os", installed.os()); tx.setProperty("game", installed.game());
                int serverPort = environment.getRequiredProperty("local.server.port", Integer.class);
                if (serverPort < 1 || serverPort > 65535) throw new IOException("Invalid running application port");
                tx.setProperty("serverPort", Integer.toString(serverPort));
                String serverAddress = environment.getProperty("server.address");
                if (serverAddress != null && !serverAddress.isBlank()) tx.setProperty("serverAddress", serverAddress);
                recordProcess(tx, "old", ProcessHandle.current());
                ProcessHandle.current().parent().ifPresent(parent -> {
                    if (parent.info().command().map(s -> Path.of(s).toAbsolutePath().normalize().startsWith(installed.root())).orElse(false)) recordProcess(tx, "launcher", parent);
                });
                if (local()) { tx.setProperty("source", "local"); tx.setProperty("localDirectory", Path.of(options.getLocalDirectory()).toRealPath().toString()); }
                if (environment.containsProperty("app.updates.test-home")) tx.setProperty("testHome", home().toString());
                ImageFiles.store(transaction.resolve("transaction.properties"), tx);
                if (cancelled.get()) throw new IOException("Update cancelled");
                publish(READY_TO_RESTART, "Starting independent updater", release, 0, 0);
                Path java = transaction.resolve("helper/runtime/bin/" + (installed.os().equals("windows") ? "java.exe" : "java"));
                Process helper = new ProcessBuilder(java.toString(), "-cp", transaction.resolve("helper/helper.jar").toString(),
                        "com.github.fmaiassistent.update.helper.UpdaterMain", "install", transaction.toString(), home().toString())
                        .directory(transaction.toFile()).redirectErrorStream(true).redirectOutput(transaction.resolve("helper-console.log").toFile()).start();
                long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                while (!Files.exists(transaction.resolve("helper-started")) && helper.isAlive() && System.nanoTime() < deadline) Thread.sleep(100);
                if (!helper.isAlive() || !Files.exists(transaction.resolve("helper-started"))) throw new IOException("Independent updater could not start; current app remains open");
                publish(INSTALLING, "Closing application for installation", release, 0, 0);
                Thread.ofPlatform().name("desktop-update-shutdown").start(() -> shutdown.accept(transaction));
            } catch (Exception e) { error(e); }
        });
    }
    private static void recordProcess(Properties tx, String key, ProcessHandle process) {
        tx.setProperty(key + "Pid", Long.toString(process.pid()));
        tx.setProperty(key + "Start", process.info().startInstant().orElseThrow().toString());
    }
    public void cancel() { if (status.phase() == DOWNLOADING || status.phase() == PREPARING) cancelled.set(true); }
    public synchronized void later() {
        if (status.phase() != AVAILABLE) return;
        try {
            Files.createDirectories(stateRoot());
            Properties prefs = new Properties(); prefs.setProperty("version", status.release().version());
            ImageFiles.store(dismissalFile(), prefs);
            publish(NO_UPDATE, "Update postponed", null, 0, 0);
        } catch (IOException e) { error(e); }
    }
    private boolean dismissed(String version) throws IOException {
        Path file = dismissalFile();
        return Files.exists(file) && ImageFiles.properties(file).getProperty("version", "").equals(version);
    }
    private Path dismissalFile() { return stateRoot().resolve(local() ? "dismissed-local.properties" : "dismissed-github.properties"); }
    private void publish(UpdateStatus.Phase phase, String message, UpdateRelease release, long done, long total) {
        status = new UpdateStatus(phase, message, installed == null ? "unpackaged" : installed.version(), release, done, total);
    }
    private void error(Exception e) {
        org.slf4j.LoggerFactory.getLogger(UpdateCoordinator.class).warn("Desktop update failed", e);
        publish(ERROR, e instanceof IOException ? e.getMessage() : "Update failed. See the local application log.", null, 0, 0);
    }
    @PreDestroy public void close() { cancelled.set(true); worker.shutdownNow(); }
}
