package com.github.fmaiassistent.tactic;

import com.github.fmaiassistent.ai.AiPromptContextContributor;
import com.github.fmaiassistent.domain.entity.TacticContextEntity;
import com.github.fmaiassistent.repository.TacticContextRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class TacticContextService implements AiPromptContextContributor {
    private static final Logger log = LoggerFactory.getLogger(TacticContextService.class);

    private final FmfTacticParser fmfParser;
    private final TacticContextProperties properties;
    private final TacticContextRepository repository;
    private final AtomicLong versions = new AtomicLong();
    private final AtomicReference<TacticContext> current =
            new AtomicReference<>(TacticContext.empty(0));
    private final ConcurrentMap<String, Long> deliveredVersions = new ConcurrentHashMap<>();
    private final AtomicBoolean aiContextEnabled = new AtomicBoolean(true);

    @Autowired
    TacticContextService(
            FmfTacticParser fmfParser,
            TacticContextProperties properties,
            TacticContextRepository repository) {
        this.fmfParser = fmfParser;
        this.properties = properties;
        this.repository = repository;
    }

    TacticContextService(FmfTacticParser fmfParser, TacticContextProperties properties) {
        this(fmfParser, properties, null);
    }

    @PostConstruct
    void restorePersistedTactic() {
        if (repository == null) {
            return;
        }
        repository.findById(1).filter(TacticContextEntity::isEnabled).ifPresent(saved -> {
            try {
                TacticContext restored = build(saved.getFileName(), saved.getFmfData(), false);
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

    public TacticContext current() {
        return current.get();
    }

    public boolean aiContextEnabled() {
        return aiContextEnabled.get();
    }

    public void setAiContextEnabled(boolean enabled) {
        if (aiContextEnabled.getAndSet(enabled) != enabled) {
            deliveredVersions.clear();
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

        return build(fileName, data.clone(), true);
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
        Long previousVersion = deliveredVersions.put(conversationKey, context.version());
        if (previousVersion != null && previousVersion == context.version()) {
            return "";
        }
        return """
                <fm26_tactic_context>
                %s
                </fm26_tactic_context>
                """.formatted(context.markdown());
    }

    private TacticContext build(String fileName, byte[] data, boolean persist) {
        FmfTacticParser.FmfMetadata metadata = fmfParser.parse(data);
        String title = metadata.tactic().name();
        if (title == null || title.isBlank()) {
            title = fileName;
        }
        String resources = metadata.resources().isEmpty()
                ? "No named resources found"
                : String.join(", ", metadata.resources());
        String markdown = "# " + title + "\n\n"
                + "Source: uploaded " + fileName + "\n\n"
                + "## FMF archive metadata\n"
                + "Internal name: " + metadata.internalName() + "\n"
                + "Contained resources: " + resources + "\n\n"
                + "## Decoded FM26 tactic\n"
                + metadata.tactic().markdown() + "\n";

        List<String> warnings = List.of();
        if (markdown.length() > properties.maxContextCharacters()) {
            markdown = markdown.substring(0, properties.maxContextCharacters()) + "\n[Context truncated]\n";
            warnings = List.of("Tactic context was truncated to "
                    + properties.maxContextCharacters() + " characters");
        }

        String fingerprint = sha256(data);
        TacticContext context = new TacticContext(
                versions.incrementAndGet(), title, "browser upload", markdown,
                List.of(fileName), warnings, TacticDefinition.from(metadata.tactic()), fingerprint);
        if (persist && repository != null) {
            repository.save(new TacticContextEntity(fileName, data, fingerprint));
        }
        current.set(context);
        log.info("Loaded uploaded FM26 tactic context title={} file={} warnings={}",
                title, fileName, warnings.size());
        return context;
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
