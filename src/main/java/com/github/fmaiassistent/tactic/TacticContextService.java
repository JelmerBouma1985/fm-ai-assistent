package com.github.fmaiassistent.tactic;

import com.github.fmaiassistent.ai.AiPromptContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class TacticContextService implements AiPromptContext {
    private static final Logger log = LoggerFactory.getLogger(TacticContextService.class);
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(".png", ".jpg", ".jpeg");
    private static final Set<String> EXTRACTED_EXTENSIONS = Set.of(
            ".tac", ".aom", ".xml", ".json", ".txt", ".yaml", ".yml", ".jsb");

    private final FmfTacticParser fmfParser;
    private final TacticImageTextExtractor imageTextExtractor;
    private final TacticContextProperties properties;
    private final AtomicLong versions = new AtomicLong();
    private final AtomicReference<TacticContext> current =
            new AtomicReference<>(TacticContext.empty(0));
    private final ConcurrentMap<String, Long> deliveredVersions = new ConcurrentHashMap<>();

    TacticContextService(
            FmfTacticParser fmfParser,
            TacticImageTextExtractor imageTextExtractor,
            TacticContextProperties properties) {
        this.fmfParser = fmfParser;
        this.imageTextExtractor = imageTextExtractor;
        this.properties = properties;
    }

    public TacticContext current() {
        return current.get();
    }

    public TacticContext loadPath(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Enter a tactic file or folder location");
        }
        Path requested = Path.of(location.strip()).toAbsolutePath().normalize();
        if (!Files.exists(requested)) {
            throw new IllegalArgumentException("Tactic path does not exist: " + requested);
        }
        List<Path> paths = Files.isDirectory(requested)
                ? discoverDirectory(requested)
                : selectedFile(requested);
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("No supported tactic files were found at " + requested);
        }
        LinkedHashMap<String, SourceFile> files = new LinkedHashMap<>();
        for (Path path : paths) {
            try {
                validateSize(path, Files.size(path));
                files.put(path.getFileName().toString(), new SourceFile(path.getFileName().toString(), path, null));
            } catch (IOException exception) {
                throw new IllegalArgumentException("Could not read " + path, exception);
            }
        }
        return build(requested.toString(), files);
    }

    public TacticContext loadUploads(Map<String, byte[]> uploads) {
        if (uploads == null || uploads.isEmpty()) {
            throw new IllegalArgumentException("Select an FM26 FMF tactic file");
        }
        LinkedHashMap<String, SourceFile> files = new LinkedHashMap<>();
        uploads.forEach((name, data) -> {
            String safeName = Path.of(name).getFileName().toString();
            validateSize(Path.of(safeName), data.length);
            if (!supported(safeName)) {
                throw new IllegalArgumentException("Unsupported tactic file: " + safeName);
            }
            files.put(safeName, new SourceFile(safeName, null, data.clone()));
        });
        return build("browser upload", files);
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

    private TacticContext build(String source, LinkedHashMap<String, SourceFile> files) {
        List<String> warnings = new ArrayList<>();
        List<Section> sections = new ArrayList<>();
        String title = null;
        boolean hasTacticalDetail = false;

        for (SourceFile file : files.values()) {
            String extension = extension(file.name());
            try {
                if (".fmf".equals(extension)) {
                    FmfTacticParser.FmfMetadata metadata = fmfParser.parse(file.bytes());
                    title = metadata.tactic().name();
                    String resources = metadata.resources().isEmpty()
                            ? "No named resources found"
                            : String.join(", ", metadata.resources());
                    sections.add(new Section("FMF archive metadata", "Internal name: "
                            + metadata.internalName() + "\nContained resources: " + resources));
                    sections.add(new Section("Decoded FM26 tactic", metadata.tactic().markdown()));
                    hasTacticalDetail = true;
                    continue;
                }
                if (IMAGE_EXTENSIONS.contains(extension)) {
                    TacticImageTextExtractor.ImageKind kind = imageKind(file.name());
                    String text = withTemporaryPath(file, path -> imageTextExtractor.extract(path, kind));
                    if (!text.isBlank()) {
                        sections.add(new Section(sectionTitle(kind, file.name()), text));
                        hasTacticalDetail = true;
                    }
                    continue;
                }
                if (EXTRACTED_EXTENSIONS.contains(extension)) {
                    String extracted = readableContent(file.bytes());
                    if (!extracted.isBlank()) {
                        sections.add(new Section("Extracted Resource Archiver data: " + file.name(), extracted));
                        hasTacticalDetail = true;
                    } else {
                        warnings.add(file.name() + " is binary and could not be converted to readable text");
                    }
                }
            } catch (RuntimeException exception) {
                warnings.add(file.name() + ": " + safeMessage(exception));
            }
        }

        if (sections.isEmpty()) {
            throw new IllegalArgumentException(warnings.isEmpty()
                    ? "No readable tactic context was found"
                    : String.join("; ", warnings));
        }
        if (!hasTacticalDetail) {
            warnings.add("No tactical roles or instructions could be decoded from the selected files");
        }
        if (title == null || title.isBlank()) {
            title = files.keySet().stream().findFirst().orElse("FM26 tactic");
        }

        StringBuilder markdown = new StringBuilder("# ").append(title).append("\n\n")
                .append("Source: ").append(source).append("\n");
        for (Section section : sections) {
            markdown.append("\n## ").append(section.title()).append("\n")
                    .append(section.content()).append("\n");
        }
        if (!warnings.isEmpty()) {
            markdown.append("\n## Import notes\n");
            warnings.forEach(warning -> markdown.append("- ").append(warning).append("\n"));
        }
        if (markdown.length() > properties.maxContextCharacters()) {
            markdown.setLength(properties.maxContextCharacters());
            markdown.append("\n[Context truncated]\n");
            warnings.add("Tactic context was truncated to " + properties.maxContextCharacters() + " characters");
        }

        TacticContext context = new TacticContext(
                versions.incrementAndGet(), title, source, markdown.toString(),
                List.copyOf(files.keySet()), warnings);
        current.set(context);
        log.info("Loaded FM26 tactic context title={} files={} warnings={}",
                title, files.size(), warnings.size());
        return context;
    }

    private List<Path> selectedFile(Path requested) {
        if (!Files.isRegularFile(requested) || !supported(requested.getFileName().toString())) {
            throw new IllegalArgumentException("Unsupported tactic file: " + requested);
        }
        return List.of(requested);
    }

    private List<Path> discoverDirectory(Path directory) {
        try (var paths = Files.walk(directory, 4)) {
            List<Path> candidates = paths.filter(Files::isRegularFile)
                    .filter(path -> supported(path.getFileName().toString()))
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        String extension = extension(name);
                        return !IMAGE_EXTENSIONS.contains(extension)
                                || name.contains("tactic")
                                || name.contains("possession");
                    })
                    .limit(100)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            long fmfCount = candidates.stream().filter(path -> ".fmf".equals(extension(path.toString()))).count();
            if (fmfCount > 1) {
                throw new IllegalArgumentException("This folder contains multiple FMF files; select the tactic FMF directly");
            }
            return candidates;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not inspect tactic folder " + directory, exception);
        }
    }

    private void validateSize(Path path, long size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Tactic file is empty: " + path.getFileName());
        }
        if (size > properties.maxFileSize().toBytes()) {
            throw new IllegalArgumentException("Tactic file is too large: " + path.getFileName());
        }
    }

    private static String readableContent(byte[] bytes) {
        int controls = 0;
        for (byte value : bytes) {
            int unsigned = Byte.toUnsignedInt(value);
            if (unsigned == 0 || unsigned < 0x09 || unsigned > 0x0d && unsigned < 0x20) {
                controls++;
            }
        }
        if (controls <= Math.max(2, bytes.length / 100)) {
            return new String(bytes, StandardCharsets.UTF_8).strip();
        }

        return "";
    }

    private static TacticImageTextExtractor.ImageKind imageKind(String fileName) {
        String normalized = fileName.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (normalized.contains("out_of_possession")) {
            return TacticImageTextExtractor.ImageKind.OUT_OF_POSSESSION;
        }
        if (normalized.contains("in_possession")) {
            return TacticImageTextExtractor.ImageKind.IN_POSSESSION;
        }
        if (normalized.contains("tactic")) {
            return TacticImageTextExtractor.ImageKind.SHAPE;
        }
        return TacticImageTextExtractor.ImageKind.OTHER;
    }

    private static String sectionTitle(TacticImageTextExtractor.ImageKind kind, String fileName) {
        return switch (kind) {
            case SHAPE -> "Shape and player roles (screenshot OCR)";
            case IN_POSSESSION -> "In-possession instructions (screenshot OCR)";
            case OUT_OF_POSSESSION -> "Out-of-possession instructions (screenshot OCR)";
            case OTHER -> "Tactic screenshot OCR: " + fileName;
        };
    }

    private static <T> T withTemporaryPath(SourceFile file, PathOperation<T> operation) {
        if (file.path() != null) {
            return operation.apply(file.path());
        }
        String suffix = extension(file.name());
        try {
            Path temporary = Files.createTempFile("fm26-tactic-", suffix);
            try {
                Files.write(temporary, file.data());
                return operation.apply(temporary);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not process uploaded tactic image", exception);
        }
    }

    private static boolean supported(String name) {
        String extension = extension(name);
        return ".fmf".equals(extension)
                || IMAGE_EXTENSIONS.contains(extension)
                || EXTRACTED_EXTENSIONS.contains(extension);
    }

    private static String extension(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        int dot = normalized.lastIndexOf('.');
        return dot < 0 ? "" : normalized.substring(dot);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private record SourceFile(String name, Path path, byte[] data) {
        byte[] bytes() {
            if (data != null) {
                return data;
            }
            try {
                return Files.readAllBytes(path);
            } catch (IOException exception) {
                throw new IllegalArgumentException("Could not read " + name, exception);
            }
        }
    }

    private record Section(String title, String content) {
    }

    @FunctionalInterface
    private interface PathOperation<T> {
        T apply(Path path);
    }
}
