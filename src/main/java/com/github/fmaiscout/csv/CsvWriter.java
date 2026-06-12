package com.github.fmaiscout.csv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class CsvWriter {
    private CsvWriter() {
    }

    public static void write(Path output, List<String> fields, List<Map<String, Object>> rows) throws IOException {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        StringBuilder text = new StringBuilder();
        text.append(fields.stream().map(CsvWriter::quote).collect(Collectors.joining(","))).append('\n');
        for (Map<String, Object> row : rows) {
            text.append(fields.stream()
                    .map(field -> quote(String.valueOf(row.getOrDefault(field, ""))))
                    .collect(Collectors.joining(","))).append('\n');
        }
        Files.writeString(output, text.toString(), StandardCharsets.UTF_8);
    }

    private static String quote(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
