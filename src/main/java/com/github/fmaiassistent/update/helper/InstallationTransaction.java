package com.github.fmaiassistent.update.helper;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** JDK-only journaled transaction. Failed new starts never automatically reopen migrated data. */
public final class InstallationTransaction {
    private final Path directory, install, staged, backup, home;
    private final Properties p;
    private final long waitMillis;
    private final ProcessStarter starter;
    @FunctionalInterface public interface ProcessStarter { Process start(List<String> command, Path directory) throws IOException; }
    public InstallationTransaction(Path directory, Path home, long waitMillis) throws IOException {
        this(directory, home, waitMillis, (command, workingDirectory) -> new ProcessBuilder(command).directory(workingDirectory.toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("restarted-app.log").toFile()).start());
    }
    public InstallationTransaction(Path directory, Path home, long waitMillis, ProcessStarter starter) throws IOException {
        this.starter = starter;
        this.directory = directory.toRealPath(); this.home = home.toRealPath(); this.waitMillis = waitMillis;
        Path stateRoot = this.home.resolve(".fm-ai-assistent/updater").toRealPath();
        if (!this.directory.getParent().equals(stateRoot) || !this.directory.getFileName().toString().matches("[0-9a-f-]{36}")) throw new IOException("Untrusted updater transaction directory");
        p = ImageFiles.properties(this.directory.resolve("transaction.properties"));
        String id = this.directory.getFileName().toString();
        if (!id.equals(p.getProperty("id"))) throw new IOException("Transaction identity mismatch");
        install = ImageFiles.safeInstall(Path.of(p.getProperty("install")), this.home);
        staged = install.resolveSibling(install.getFileName() + ".update-" + id);
        backup = install.resolveSibling(install.getFileName() + ".backup-" + id);
        if (!staged.toString().equals(p.getProperty("staged")) || !backup.toString().equals(p.getProperty("backup"))) throw new IOException("Unsafe transaction paths");
        for (var path : List.of(staged, backup)) if (Files.isSymbolicLink(path)) throw new IOException("Linked transaction image");
    }
    public Properties properties() { return p; }
    public void state(String state) throws IOException {
        p.setProperty("state", state); ImageFiles.store(directory.resolve("transaction.properties"), p);
        Files.writeString(directory.resolve("updater.log"), Instant.now() + " " + state + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    public void execute() throws Exception {
        if (!p.getProperty("state", "").equals("PREPARED")) throw new IOException("Transaction already started; use recover");
        validateImage(staged, "newVersion"); validateImage(install, "oldVersion");
        state("WAITING_FOR_SHUTDOWN");
        Files.createFile(directory.resolve("helper-started"));
        waitForOldProcesses();
        ensureNoImageProcesses();
        if (!Files.isRegularFile(directory.resolve("clean-shutdown"))) throw new IOException("Application did not acknowledge clean database shutdown");
        backupData();
        state("BACKUP_PENDING");
        try {
            ImageFiles.move(install, backup);
            state("OLD_BACKED_UP");
            ImageFiles.move(staged, install);
            state("NEW_INSTALLED");
        } catch (Exception failure) {
            recover(); throw failure;
        }
        // Write intent BEFORE spawning: after a crash migrations must be assumed to have run.
        state("RESTARTING");
        Process child;
        try { child = startNew(); }
        catch (IOException e) { recover(); throw e; }
        p.setProperty("newPid", Long.toString(child.pid()));
        child.toHandle().info().startInstant().ifPresent(t -> p.setProperty("newStart", t.toString()));
        state("AWAITING_HEALTH");
        long deadline = System.nanoTime() + Duration.ofMillis(waitMillis).toNanos();
        while (System.nanoTime() < deadline && child.isAlive()) {
            if (healthy()) { state("COMPLETED"); return; }
            Thread.sleep(200);
        }
        if (healthy()) { state("COMPLETED"); return; }
        // Do not kill an unknown/stale PID, or swap while a new JVM might still be alive.
        state("RECOVERY_REQUIRED");
        if (!child.isAlive()) recover();
        throw new IOException("New application did not acknowledge readiness. Close it, then run helper recover. Data backup retained; no old code was started against possibly migrated data.");
    }
    private Process startNew() throws IOException {
        List<String> command = new ArrayList<>();
        command.add(ImageFiles.launcher(install, p.getProperty("os")).toString());
        command.add("--app.updates.transaction=" + p.getProperty("id"));
        if (p.getProperty("source", "github").equals("local")) {
            command.add("--app.updates.source=local"); command.add("--app.updates.developer-mode=true");
            command.add("--app.updates.local-directory=" + p.getProperty("localDirectory"));
        }
        if (p.containsKey("testHome")) command.add("--app.updates.test-home=" + p.getProperty("testHome"));
        return starter.start(command, install);
    }
    private boolean healthy() throws IOException {
        Path ack = directory.resolve("healthy.properties");
        if (!Files.isRegularFile(ack)) return false;
        var ready = ImageFiles.properties(ack);
        return p.getProperty("id").equals(ready.getProperty("id")) && p.getProperty("newVersion").equals(ready.getProperty("version"));
    }
    private void validateImage(Path image, String versionKey) throws IOException {
        var m = ImageFiles.metadata(image);
        if (!m.getProperty("version").equals(p.getProperty(versionKey)) || !m.getProperty("os").equals(p.getProperty("os"))
                || !m.getProperty("game").equals(p.getProperty("game"))) throw new IOException("Transaction image identity mismatch");
    }
    private void backupData() throws IOException {
        state("BACKING_UP_DATA");
        Path data = home.resolve(".fm-ai-assistent"), snapshot = directory.resolve("data-backup");
        Files.createDirectory(snapshot);
        try (var paths = Files.list(data)) {
            for (Path path : paths.toList()) {
                if (path.getFileName().toString().equals("updater")) continue;
                if (Files.isSymbolicLink(path)) throw new IOException("Cannot back up linked user data");
                if (Files.isDirectory(path)) ImageFiles.copyTree(path, snapshot.resolve(path.getFileName()));
                else if (Files.isRegularFile(path)) Files.copy(path, snapshot.resolve(path.getFileName()), StandardCopyOption.COPY_ATTRIBUTES);
                else throw new IOException("Cannot back up special user data file");
            }
        }
        state("DATA_BACKED_UP");
    }
    private void waitForOldProcesses() throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(waitMillis).toNanos();
        for (String key : List.of("old", "launcher")) {
            if (!p.containsKey(key + "Pid")) continue;
            long pid = Long.parseLong(p.getProperty(key + "Pid"));
            String start = p.getProperty(key + "Start", "");
            while (true) {
                var process = ProcessHandle.of(pid);
                if (process.isEmpty() || !process.get().isAlive()) break;
                if (start.isBlank() || !process.get().info().startInstant().map(Instant::toString).orElse("").equals(start)) throw new IOException("PID identity changed; refusing update");
                if (System.nanoTime() >= deadline) throw new IOException("Application did not exit before update timeout");
                Thread.sleep(200);
            }
        }
    }
    /** Conservative, idempotent recovery. Never deletes an image and never launches old code. */
    public void recover() throws IOException {
        String previous = p.getProperty("state", "");
        if (previous.equals("COMPLETED") || previous.equals("RECOVERED") || previous.equals("DATA_REVIEW_REQUIRED")) return;
        ensureNoImageProcesses();
        for (String key : List.of("old", "launcher", "new")) {
            if (p.containsKey(key + "Pid")) {
                var process = ProcessHandle.of(Long.parseLong(p.getProperty(key + "Pid")));
                if (process.isPresent() && process.get().isAlive()) throw new IOException("Close application processes before recovery");
            }
        }
        if (Files.exists(backup)) {
            validateImage(backup, "oldVersion");
            if (Files.exists(install)) {
                validateImage(install, "newVersion");
                ImageFiles.move(install, install.resolveSibling(install.getFileName() + ".failed-" + p.getProperty("id")));
            }
            ImageFiles.move(backup, install);
        }
        boolean launched = Set.of("RESTARTING", "AWAITING_HEALTH", "RECOVERY_REQUIRED").contains(previous);
        state(launched ? "DATA_REVIEW_REQUIRED" : "RECOVERED");
        if (launched) {
            Files.writeString(install.resolve(".updater-data-review"), directory.toString());
            Files.writeString(directory.resolve("RECOVERY.txt"), "Old binaries restored. Do not launch until data compatibility has been reviewed. A consistent pre-update copy is in data-backup. Back up current data before explicitly restoring it. See docs/desktop-auto-updater.md.");
        }
    }
    private void ensureNoImageProcesses() throws IOException {
        try (var processes = ProcessHandle.allProcesses()) {
            if (processes.anyMatch(process -> process.isAlive() && process.info().command().map(command -> {
                try { return Path.of(command).toAbsolutePath().normalize().startsWith(install); }
                catch (InvalidPathException e) { return false; }
            }).orElse(false))) throw new IOException("An application process is still using the installation; close it before continuing");
        }
    }
}
