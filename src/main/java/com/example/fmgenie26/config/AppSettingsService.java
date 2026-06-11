package com.example.fmgenie26.config;

import com.example.fmgenie26.FmGenie26Application;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Optional;
import java.util.Properties;

@Service
public class AppSettingsService {
    private static final String SETTINGS_FILE = "fm-genie26.properties";
    private static final String SETTINGS_FILE_PROPERTY = "fmgenie26.settings.file";
    private static final String CURRENCY_KEY = "currency";

    private final Path settingsPath;

    public AppSettingsService() {
        this.settingsPath = resolveSettingsPath().toAbsolutePath().normalize();
        ensureSettingsFileExists();
    }

    public MoneyCurrency currency() {
        return MoneyCurrency.fromPropertyValue(load().getProperty(CURRENCY_KEY));
    }

    public void saveCurrency(MoneyCurrency currency) {
        Properties properties = load();
        properties.setProperty(CURRENCY_KEY, (currency == null ? MoneyCurrency.POUND : currency).propertyValue());
        save(properties);
    }

    public Path settingsPath() {
        return settingsPath;
    }

    private Properties load() {
        Properties properties = new Properties();
        if (!Files.exists(settingsPath)) {
            return properties;
        }
        try (InputStream input = Files.newInputStream(settingsPath)) {
            properties.load(input);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not load settings from " + settingsPath, ex);
        }
        return properties;
    }

    private void save(Properties properties) {
        try {
            Path parent = settingsPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream output = Files.newOutputStream(settingsPath)) {
                properties.store(output, "FM Genie 26 settings");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not save settings to " + settingsPath, ex);
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
            CodeSource codeSource = FmGenie26Application.class.getProtectionDomain().getCodeSource();
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
