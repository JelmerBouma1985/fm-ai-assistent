package com.github.fmaiassistent.tactic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
class TacticOcrService implements TacticImageTextExtractor {
    private static final Logger log = LoggerFactory.getLogger(TacticOcrService.class);

    private final TacticContextProperties properties;

    TacticOcrService(TacticContextProperties properties) {
        this.properties = properties;
    }

    @Override
    public String extract(Path image, ImageKind kind) {
        BufferedImage source = readImage(image);
        Path ocrInput = image;
        try {
            if (kind == ImageKind.SHAPE) {
                ocrInput = prepareShapeImage(source);
            }
            return runTesseract(ocrInput, kind);
        } finally {
            if (!ocrInput.equals(image)) {
                try {
                    Files.deleteIfExists(ocrInput);
                } catch (IOException exception) {
                    log.debug("Could not delete temporary tactic OCR image {}", ocrInput, exception);
                }
            }
        }
    }

    private String runTesseract(Path image, ImageKind kind) {
        List<String> command = List.of(
                properties.ocrExecutable(), image.toString(), "stdout", "--psm", "6");
        try {
            Process process = new ProcessBuilder(command).start();
            CompletableFuture<String> stdout = read(process.getInputStream());
            CompletableFuture<String> stderr = read(process.getErrorStream());
            if (!process.waitFor(properties.ocrTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Tactic screenshot OCR timed out");
            }
            String diagnostics = stderr.join().strip();
            if (process.exitValue() != 0) {
                throw new IllegalStateException(diagnostics.isBlank()
                        ? "Tesseract could not read the tactic screenshot"
                        : diagnostics);
            }
            if (!diagnostics.isBlank()) {
                log.debug("Tactic OCR diagnostic: {}", diagnostics);
            }
            return clean(stdout.join(), kind);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Tactic OCR is unavailable. Install Tesseract or configure app.ai.tactic-context.ocr-executable",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Tactic screenshot OCR was interrupted", exception);
        }
    }

    private static BufferedImage readImage(Path image) {
        try {
            BufferedImage source = ImageIO.read(image.toFile());
            if (source == null) {
                throw new IllegalArgumentException("Unsupported or damaged tactic image: " + image.getFileName());
            }
            return source;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not read tactic image: " + image.getFileName(), exception);
        }
    }

    private static Path prepareShapeImage(BufferedImage source) {
        int width = Math.max(1, (int) Math.round(source.getWidth() * 2.5));
        int height = Math.max(1, (int) Math.round(source.getHeight() * 2.5));
        BufferedImage gray = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = gray.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        try {
            Path temporary = Files.createTempFile("fm26-tactic-shape-", ".png");
            if (!ImageIO.write(gray, "png", temporary.toFile())) {
                throw new IllegalStateException("Could not prepare tactic screenshot for OCR");
            }
            return temporary;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not prepare tactic screenshot for OCR", exception);
        }
    }

    private static CompletableFuture<String> read(java.io.InputStream input) {
        return CompletableFuture.supplyAsync(() -> {
            try (input) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private static String clean(String raw, ImageKind kind) {
        List<String> lines = new ArrayList<>();
        for (String original : raw.replace('\r', '\n').split("\\n")) {
            String line = original.strip()
                    .replaceFirst("^[‘'`]+", "")
                    .replace("Tackiing", "Tackling")
                    .replace("Ballin Play", "Ball in Play")
                    .replace("Buildup", "Build-up")
                    .replaceFirst("\\s+Select$", "");
            if (line.isBlank() || line.startsWith("Overview (")) {
                continue;
            }
            lines.add(line);
        }
        if (kind == ImageKind.SHAPE) {
            lines.add("OCR preserves the two side-by-side In Possession and Out of Possession panels; minor role/name recognition errors are possible.");
        }
        return String.join("\n", lines);
    }
}
