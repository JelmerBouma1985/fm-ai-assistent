package com.github.fmaiscout.cli;

import com.github.fmaiscout.csv.CsvWriter;
import com.github.fmaiscout.fm.FmOffsets;
import com.github.fmaiscout.player.PlayerExporter;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class ExportCommandLineRunner implements ApplicationRunner {
    private final ApplicationContext context;
    private final PlayerExporter exporter = new PlayerExporter();

    public ExportCommandLineRunner(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("export-club")) {
            return;
        }
        int pid = Integer.parseInt(required(args, "pid"));
        String club = required(args, "export-club");
        Path output = Path.of(required(args, "output"));
        int build = parseInt(args.getOptionValues("build") == null ? "0x" + Integer.toHexString(FmOffsets.DEFAULT_BUILD) : args.getOptionValues("build").get(0));
        Long gamePluginBase = args.getOptionValues("game-plugin-base") == null ? null : parseLong(args.getOptionValues("game-plugin-base").get(0));

        PlayerExporter.ExportResult result = exporter.exportClub(pid, club, build, gamePluginBase);
        CsvWriter.write(output, PlayerExporter.FIELD_NAMES, result.rows());
        System.out.printf("game_date=%s%n", result.gameDate().isBlank() ? "not_found" : result.gameDate());
        System.out.printf("exported=%d output=%s%n", result.rows().size(), output);
        int exitCode = SpringApplication.exit(context, () -> 0);
        System.exit(exitCode);
    }

    private static String required(ApplicationArguments args, String name) {
        var values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("--" + name + " is required");
        }
        return values.get(0);
    }

    private static int parseInt(String value) {
        return (int) Long.decode(value).longValue();
    }

    private static long parseLong(String value) {
        return Long.decode(value);
    }
}
