package com.github.fmaiassistent.tactic;

import com.github.fmaiassistent.ai.AiPromptContextContributor;
import com.github.fmaiassistent.domain.entity.TacticContextEntity;
import com.github.fmaiassistent.repository.TacticContextRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class TacticContextService implements AiPromptContextContributor {
    private static final Logger log = LoggerFactory.getLogger(TacticContextService.class);

    private final FmfTacticParser fmfParser;
    private final TacticContextProperties properties;
    private final TacticContextRepository repository;
    private final TacticDirectoryResolver disk;
    private final AtomicLong versions = new AtomicLong();
    private final AtomicReference<TacticContext> current =
            new AtomicReference<>(TacticContext.empty(0));
    private final Cache<String, Long> deliveredVersions = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofHours(12))
            .build();
    private final AtomicBoolean aiContextEnabled = new AtomicBoolean(true);

    @Autowired
    TacticContextService(
            FmfTacticParser fmfParser,
            TacticContextProperties properties,
            TacticContextRepository repository,
            TacticDirectoryResolver disk) {
        this.fmfParser = fmfParser;
        this.properties = properties;
        this.repository = repository;
        this.disk = disk == null ? new TacticDirectoryResolver() : disk;
    }

    TacticContextService(
            FmfTacticParser fmfParser,
            TacticContextProperties properties,
            TacticContextRepository repository) {
        this(fmfParser, properties, repository, new TacticDirectoryResolver());
    }

    TacticContextService(FmfTacticParser fmfParser, TacticContextProperties properties) {
        this(fmfParser, properties, null, new TacticDirectoryResolver());
    }

    TacticContextService(
            FmfTacticParser fmfParser,
            TacticContextProperties properties,
            TacticContextRepository repository,
            java.nio.file.Path diskDirectory) {
        this(fmfParser, properties, repository, new TacticDirectoryResolver(diskDirectory));
    }

    @PostConstruct
    void restorePersistedTactic() {
        if (repository == null) {
            return;
        }
        repository.findById(1).filter(TacticContextEntity::isEnabled).ifPresent(saved -> {
            try {
                if (isDiskSource(saved)) {
                    restoreDiskSource(saved);
                    return;
                }
                TacticContext restored = build(saved.getFileName(), saved.getFmfData(), false, "UPLOAD", null);
                log.info("Restored FM26 tactic context title={} fingerprint={}",
                        restored.title(), restored.fingerprint());
            } catch (RuntimeException exception) {
                String warning = "Saved tactic could not be restored; upload it again: " + safeMessage(exception);
                current.set(new TacticContext(
                        versions.incrementAndGet(), "Saved tactic unavailable", "local database",
                        null, List.of(saved.getFileName()), List.of(warning), null, saved.getFingerprint()));
                log.warn(warning);
            }
        });
    }

    private void restoreDiskSource(TacticContextEntity saved) {
        java.nio.file.Path path = java.nio.file.Path.of(saved.getSourcePath());
        byte[] diskBytes = null;
        boolean diskReadable = false;
        try {
            diskBytes = java.nio.file.Files.readAllBytes(path);
            diskReadable = true;
        } catch (RuntimeException | java.io.IOException exception) {
            log.warn("Remembered disk tactic unreadable, using cached copy: {}", safeMessage(exception));
        }
        if (diskReadable) {
            String diskFingerprint = sha256(diskBytes);
            if (!diskFingerprint.equals(saved.getFingerprint())) {
                // Silent reload: the disk file changed since last start.
                try {
                    TacticContext reloaded = build(
                            path.getFileName().toString(), diskBytes, true, "DISK", path.toString());
                    log.info("Reloaded changed disk tactic title={} fingerprint={}",
                            reloaded.title(), reloaded.fingerprint());
                    return;
                } catch (RuntimeException exception) {
                    log.warn("Changed disk tactic could not be parsed, using cached copy: {}",
                            safeMessage(exception));
                }
            } else {
                try {
                    TacticContext restored = buildFromBytesWithWarning(
                            saved.getFileName(), diskBytes, saved.getFingerprint(), "DISK", path.toString(), List.of());
                    current.set(restored);
                    log.info("Restored remembered disk tactic title={} fingerprint={}",
                            restored.title(), restored.fingerprint());
                    return;
                } catch (RuntimeException exception) {
                    log.warn("Remembered disk tactic could not be parsed, using cached copy: {}",
                            safeMessage(exception));
                }
            }
        }
        TacticContext cached = buildFromBytesWithWarning(
                saved.getFileName(),
                saved.getFmfData(),
                saved.getFingerprint(),
                "DISK",
                saved.getSourcePath(),
                List.of("Disk file unavailable — using cached copy. Choose the tactic again to re-link it."));
        current.set(cached);
        log.warn("Remembered disk tactic missing, using cached copy path={}", saved.getSourcePath());
    }

    private static boolean isDiskSource(TacticContextEntity saved) {
        return "DISK".equalsIgnoreCase(saved.getSourceKind())
                && saved.getSourcePath() != null && !saved.getSourcePath().isBlank();
    }

    public TacticContext current() {
        return current.get();
    }

    public boolean aiContextEnabled() {
        return aiContextEnabled.get();
    }

    public void setAiContextEnabled(boolean enabled) {
        if (aiContextEnabled.getAndSet(enabled) != enabled) {
            deliveredVersions.invalidateAll();
        }
    }

    public TacticContext loadUploads(Map<String, byte[]> uploads) {
        if (uploads == null || uploads.isEmpty()) {
            throw new IllegalArgumentException("Choose a Football Manager .fmf tactic file");
        }
        if (uploads.size() != 1) {
            throw new IllegalArgumentException("Upload exactly one Football Manager .fmf tactic file");
        }

        Map.Entry<String, byte[]> upload = uploads.entrySet().iterator().next();
        String fileName = safeFileName(upload.getKey());
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".fmf")) {
            throw new IllegalArgumentException("Only a Football Manager .fmf tactic file can be uploaded");
        }
        byte[] data = upload.getValue();
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Tactic file is empty: " + fileName);
        }
        if (data.length > properties.maxFileSize().toBytes()) {
            throw new IllegalArgumentException("Tactic file is too large: " + fileName);
        }

        return build(fileName, data.clone(), true, "UPLOAD", null);
    }

    public java.nio.file.Path diskDirectory() {
        return disk.directory();
    }

    public List<TacticDirectoryResolver.DiskTactic> listDiskTactics() {
        try {
            return disk.listTactics();
        } catch (RuntimeException exception) {
            log.warn("Could not list disk tactics: {}", safeMessage(exception));
            return List.of();
        }
    }

    public TacticContext loadFromDisk(java.nio.file.Path path) {
        if (path == null) {
            throw new IllegalArgumentException("Choose a tactic file from the FM26 tactics folder");
        }
        java.nio.file.Path resolved = path.toAbsolutePath().normalize();
        if (!disk.contains(resolved)) {
            throw new IllegalArgumentException("Tactic file is outside the FM26 tactics folder");
        }
        String fileName = resolved.getFileName().toString();
        if (!TacticDirectoryResolver.isFmfFileName(fileName)) {
            throw new IllegalArgumentException("Only a Football Manager .fmf tactic file can be loaded");
        }
        byte[] data;
        try {
            data = java.nio.file.Files.readAllBytes(resolved);
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Could not read tactic file: " + fileName, exception);
        }
        if (data.length == 0) {
            throw new IllegalArgumentException("Tactic file is empty: " + fileName);
        }
        if (data.length > properties.maxFileSize().toBytes()) {
            throw new IllegalArgumentException("Tactic file is too large: " + fileName);
        }
        return build(fileName, data, true, "DISK", resolved.toString());
    }

    public TacticContext clear() {
        if (repository != null) {
            repository.deleteById(1);
        }
        TacticContext empty = TacticContext.empty(versions.incrementAndGet());
        current.set(empty);
        return empty;
    }

    public String enrich(String conversationKey, String userMessage) {
        String context = contextFor(conversationKey);
        if (context.isBlank()) {
            return userMessage;
        }
        return context + "\n\nUser message:\n" + userMessage;
    }

    @Override
    public String contextFor(String conversationKey) {
        if (!aiContextEnabled.get()) {
            return "";
        }
        TacticContext context = current.get();
        if (!context.active()) {
            return "";
        }
        Long previousVersion = deliveredVersions.getIfPresent(conversationKey);
        deliveredVersions.put(conversationKey, context.version());
        if (previousVersion != null && previousVersion == context.version()) {
            return "";
        }
        return """
                <fm26_tactic_context>
                %s
                </fm26_tactic_context>
                """.formatted(context.markdown());
    }

    private TacticContext build(String fileName, byte[] data, boolean persist, String sourceKind, String sourcePath) {
        // Fail fast on corrupt archives before persisting anything.
        fmfParser.parse(data);
        String fingerprint = sha256(data);
        return buildFromBytesWithWarning(fileName, data, fingerprint, sourceKind, sourcePath, List.of(), persist);
    }

    private TacticContext buildFromBytesWithWarning(
            String fileName, byte[] data, String fingerprint, String sourceKind, String sourcePath,
            List<String> warnings) {
        FmfTacticParser.FmfMetadata metadata = fmfParser.parse(data);
        String title = metadata.tactic().name();
        if (title == null || title.isBlank()) {
            title = fileName;
        }
        boolean disk = "DISK".equalsIgnoreCase(sourceKind);
        String sourceLine = disk && sourcePath != null
                ? "Source: disk " + sourcePath + "\n"
                : "Source: uploaded " + fileName + "\n";
        String sourceLabel = disk ? "local disk" : "browser upload";
        String markdown = "# " + title + "\n\n"
                + sourceLine + "\n"
                + "## FMF archive metadata\n"
                + "Internal name: " + metadata.internalName() + "\n"
                + "Contained resources: " + String.join(", ", metadata.resources()) + "\n\n"
                + "## Decoded FM26 tactic\n"
                + metadata.tactic().markdown() + "\n";
        if (markdown.length() > properties.maxContextCharacters()) {
            markdown = markdown.substring(0, properties.maxContextCharacters()) + "\n[Context truncated]\n";
            warnings = List.of("Tactic context was truncated to "
                    + properties.maxContextCharacters() + " characters");
        }
        TacticContext context = new TacticContext(
                versions.incrementAndGet(), title, sourceLabelWithPath(sourceLabel, sourceKind, sourcePath),
                markdown, List.of(fileName), warnings, TacticDefinition.from(metadata.tactic()), fingerprint);
        current.set(context);
        return context;
    }

    private TacticContext buildFromBytesWithWarning(
            String fileName, byte[] data, String fingerprint, String sourceKind, String sourcePath,
            List<String> warnings, boolean persist) {
        TacticContext context = buildFromBytesWithWarning(fileName, data, fingerprint, sourceKind, sourcePath, warnings);
        if (persist && repository != null) {
            repository.save(new TacticContextEntity(fileName, data, fingerprint, sourceKind, sourcePath));
        }
        log.info("Loaded FM26 tactic context title={} file={} source={} warnings={}",
                context.title(), fileName, sourceKind, warnings.size());
        return context;
    }

    private static String sourceLabelWithPath(String label, String sourceKind, String sourcePath) {
        if ("DISK".equalsIgnoreCase(sourceKind) && sourcePath != null && !sourcePath.isBlank()) {
            return label + ": " + sourcePath;
        }
        return label;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null || throwable.getMessage().isBlank()
                ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }

    private static String safeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "uploaded-tactic";
        }
        try {
            return Path.of(name).getFileName().toString();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid tactic file name", exception);
        }
    }
}
