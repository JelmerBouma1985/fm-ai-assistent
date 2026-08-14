package com.github.fmaiassistent.tactic;

import com.github.fmaiassistent.ai.AiPromptContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class TacticContextService implements AiPromptContext {
    private static final Logger log = LoggerFactory.getLogger(TacticContextService.class);

    private final FmfTacticParser fmfParser;
    private final TacticContextProperties properties;
    private final AtomicLong versions = new AtomicLong();
    private final AtomicReference<TacticContext> current =
            new AtomicReference<>(TacticContext.empty(0));
    private final ConcurrentMap<String, Long> deliveredVersions = new ConcurrentHashMap<>();

    TacticContextService(FmfTacticParser fmfParser, TacticContextProperties properties) {
        this.fmfParser = fmfParser;
        this.properties = properties;
    }

    public TacticContext current() {
        return current.get();
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

        return build(fileName, data.clone());
    }

    public TacticContext clear() {
        TacticContext empty = TacticContext.empty(versions.incrementAndGet());
        current.set(empty);
        return empty;
    }

    @Override
    public String enrich(String conversationKey, String userMessage) {
        TacticContext context = current.get();
        if (!context.active()) {
            return userMessage;
        }
        Long previousVersion = deliveredVersions.put(conversationKey, context.version());
        if (previousVersion != null && previousVersion == context.version()) {
            return userMessage;
        }
        return """
                The following is the user's currently selected Football Manager 2026 tactic, decoded directly from its FMF file. Use it as factual context when the request concerns tactics, roles, squad fit, recruitment, or match analysis. Mention uncertainty instead of inventing details that are not present in the decoded context.

                <fm26_tactic_context>
                %s
                </fm26_tactic_context>

                User message:
                %s
                """.formatted(context.markdown(), userMessage);
    }

    private TacticContext build(String fileName, byte[] data) {
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

        TacticContext context = new TacticContext(
                versions.incrementAndGet(), title, "browser upload", markdown,
                List.of(fileName), warnings);
        current.set(context);
        log.info("Loaded uploaded FM26 tactic context title={} file={} warnings={}",
                title, fileName, warnings.size());
        return context;
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
