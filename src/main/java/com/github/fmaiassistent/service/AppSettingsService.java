package com.github.fmaiassistent.service;

import com.github.fmaiassistent.FmAiAssistentApplication;
import com.github.fmaiassistent.domain.enums.MoneyCurrency;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class AppSettingsService {
    private static final String SETTINGS_FILE = "fm-ai-assistent.properties";
    private static final String SETTINGS_FILE_PROPERTY = "fmaiassistent.settings.file";
    private static final String CURRENCY_KEY = "currency";

    private final Path settingsPath;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile boolean unreadableBacking;

    public AppSettingsService() {
        this(resolveSettingsPath());
    }

    AppSettingsService(Path settingsPath) {
        this.settingsPath = settingsPath.toAbsolutePath().normalize();
        ensureSettingsFileExists();
    }

    public MoneyCurrency currency() {
        return MoneyCurrency.fromPropertyValue(load().getProperty(CURRENCY_KEY));
    }

    public void saveCurrency(MoneyCurrency currency) {
        lock.writeLock().lock();
        try {
            Properties properties = load();
            properties.setProperty(CURRENCY_KEY, (currency == null ? MoneyCurrency.POUND : currency).propertyValue());
            save(properties);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Path settingsPath() {
        return settingsPath;
    }

    public String toolPolicy() { return load().getProperty("tools.policy"); }

    /** Uses the same atomic preferences write as currency; preserves unrelated settings. */
    public void saveToolPolicy(String value, boolean preserveRecoveryCopy) {
        lock.writeLock().lock();
        try {
            Properties properties = load();
            properties.setProperty("tools.policy", value);
            save(properties, preserveRecoveryCopy);
        } finally { lock.writeLock().unlock(); }
    }

    private Properties load() {
        lock.readLock().lock();
        try {
            Properties properties = new Properties();
            if (!Files.exists(settingsPath)) {
                return properties;
            }
            try (InputStream input = Files.newInputStream(settingsPath)) {
                byte[] bytes = input.readNBytes(512 * 1024 + 1);
                if (bytes.length > 512 * 1024) throw new IOException("Settings exceed size limit");
                properties.load(new java.io.ByteArrayInputStream(bytes));
            } catch (IOException | IllegalArgumentException ex) {
                unreadableBacking = true;
                // Keep the app usable and surface the recovery notice in Tools. No writes on read.
                Properties fallback = new Properties();
                fallback.setProperty("tools.policy", "unreadable-backing-preferences");
                return fallback;
            }
            return properties;
        } finally {
            lock.readLock().unlock();
        }
    }

    private void save(Properties properties) {
        save(properties, false);
    }

    private void save(Properties properties, boolean preserveRecoveryCopy) {
        lock.writeLock().lock();
        try {
            Path parent = settingsPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = Files.createTempFile(parent, settingsPath.getFileName().toString(), ".tmp");
            try {
                try (OutputStream output = Files.newOutputStream(temporary)) {
                    properties.store(output, "FM AI Assistent settings");
                }
                if ((preserveRecoveryCopy || unreadableBacking) && Files.exists(settingsPath)) {
                    Files.copy(settingsPath, settingsPath.resolveSibling(settingsPath.getFileName()
                            + ".recovery-" + java.util.UUID.randomUUID()));
                }
                try {
                    Files.move(temporary, settingsPath,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, settingsPath, StandardCopyOption.REPLACE_EXISTING);
                }
                unreadableBacking = false;
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not save settings to " + settingsPath, ex);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void ensureSettingsFileExists() {
        if (Files.exists(settingsPath)) {
            return;
        }
        Properties properties = new Properties();
        properties.setProperty(CURRENCY_KEY, MoneyCurrency.POUND.propertyValue());
        save(properties);
    }

    private static Path resolveSettingsPath() {
        String explicitPath = System.getProperty(SETTINGS_FILE_PROPERTY);
        if (explicitPath != null && !explicitPath.isBlank()) {
            return Path.of(explicitPath);
        }
        return applicationDirectory().resolve(SETTINGS_FILE);
    }

    private static Path applicationDirectory() {
        Optional<Path> nativeExecutable = currentProcessCommand()
                .filter(command -> !isJavaLauncher(command));
        if (nativeExecutable.isPresent()) {
            return nativeExecutable.get().getParent();
        }
        Optional<Path> jar = jarFromJavaCommand();
        if (jar.isPresent()) {
            return jar.get().getParent();
        }
        try {
            CodeSource codeSource = FmAiAssistentApplication.class.getProtectionDomain().getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null) {
                Path location = Path.of(codeSource.getLocation().toURI()).toAbsolutePath();
                return Files.isRegularFile(location) ? location.getParent() : location;
            }
        } catch (URISyntaxException | RuntimeException ignored) {
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath();
    }

    private static Optional<Path> currentProcessCommand() {
        try {
            return ProcessHandle.current()
                    .info()
                    .command()
                    .filter(value -> !value.isBlank())
                    .map(value -> Path.of(value).toAbsolutePath())
                    .filter(Files::isRegularFile);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private static boolean isJavaLauncher(Path command) {
        String fileName = command.getFileName() == null ? "" : command.getFileName().toString();
        return fileName.equals("java") || fileName.equals("java.exe") || fileName.equals("javaw") || fileName.equals("javaw.exe");
    }

    private static Optional<Path> jarFromJavaCommand() {
        String command = System.getProperty("sun.java.command");
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }
        String firstToken = command.trim().split("\\s+", 2)[0];
        if (!firstToken.endsWith(".jar")) {
            return Optional.empty();
        }
        Path jar = Path.of(firstToken).toAbsolutePath().normalize();
        return Files.isRegularFile(jar) ? Optional.of(jar) : Optional.empty();
    }
}
