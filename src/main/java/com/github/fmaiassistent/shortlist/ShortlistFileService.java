package com.github.fmaiassistent.shortlist;

import com.github.fmaiassistent.domain.entity.PlayerEntity;
import com.github.fmaiassistent.service.PlayerDatabaseService;
import com.github.fmaiassistent.steam.SteamLibraries;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class ShortlistFileService {
    private static final String OUTPUT_DIRECTORY_PROPERTY = "fmaiassistent.shortlists.directory";

    private final FmfShortlistFile fmf;
    private final PlayerDatabaseService players;
    private final Path outputDirectory;
    private final Deque<CreatedShortlist> recent = new ConcurrentLinkedDeque<>();
    private static final int MAX_RECENT = 10;

    @Autowired
    public ShortlistFileService(FmfShortlistFile fmf, PlayerDatabaseService players) {
        this(fmf, players, resolveOutputDirectory());
    }

    ShortlistFileService(FmfShortlistFile fmf, PlayerDatabaseService players, Path outputDirectory) {
        this.fmf = fmf;
        this.players = players;
        this.outputDirectory = outputDirectory.toAbsolutePath().normalize();
    }

    public CreatedShortlist create(String shortlistName, List<Long> playerUniqueIds) {
        if (playerUniqueIds == null || playerUniqueIds.isEmpty()) {
            throw new IllegalArgumentException("At least one player unique ID is required");
        }
        Map<Long, PlayerEntity> byUniqueId = new LinkedHashMap<>();
        for (PlayerEntity player : players.findAllPlayerEntities()) {
            if (player.getUniqueId() != null && player.getUniqueId() > 0) {
                byUniqueId.putIfAbsent(player.getUniqueId(), player);
            }
        }
        if (byUniqueId.isEmpty()) {
            throw new IllegalStateException(
                    "Player Unique IDs are unavailable. Select Load data again before creating a shortlist.");
        }
        List<PlayerEntity> selected = new ArrayList<>();
        List<Long> missing = new ArrayList<>();
        for (Long uniqueId : playerUniqueIds.stream().distinct().toList()) {
            PlayerEntity player = byUniqueId.get(uniqueId);
            if (player == null) {
                missing.add(uniqueId);
            } else {
                selected.add(player);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Unknown FM26 player unique IDs in the loaded save: " + missing);
        }

        byte[] bytes = fmf.write(shortlistName, selected.stream().map(PlayerEntity::getUniqueId).toList());
        try {
            Files.createDirectories(outputDirectory);
            Path path = availablePath(outputDirectory, fileStem(shortlistName));
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            CreatedShortlist created = new CreatedShortlist(
                    shortlistName.strip(),
                    path.toAbsolutePath().normalize(),
                    bytes.length,
                    selected.stream().map(PlayerEntity::getName).toList(),
                    selected.stream().map(PlayerEntity::getUniqueId).toList(),
                    OffsetDateTime.now().toString());
            recent.addFirst(created);
            while (recent.size() > MAX_RECENT) {
                recent.removeLast();
            }
            return created;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write the FM26 shortlist to " + outputDirectory, exception);
        }
    }

    /** Session-only history of created shortlists, newest first. Files live on disk; this list does not persist. */
    public List<CreatedShortlist> recent() {
        return List.copyOf(recent);
    }

    private static Path availablePath(Path directory, String stem) {
        Path candidate = directory.resolve(stem + ".fmf");
        for (int suffix = 2; Files.exists(candidate); suffix++) {
            candidate = directory.resolve(stem + "-" + suffix + ".fmf");
        }
        return candidate;
    }

    private static String fileStem(String shortlistName) {
        if (shortlistName == null || shortlistName.isBlank()) {
            throw new IllegalArgumentException("shortlistName is required");
        }
        String stem = shortlistName.strip()
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("\\s+", " ");
        if (stem.equals(".") || stem.equals("..") || stem.isBlank()) {
            return "fm-ai-shortlist";
        }
        return stem.length() > 120 ? stem.substring(0, 120).strip() : stem;
    }

    private static Path resolveOutputDirectory() {
        return resolveOutputDirectory(Path.of(System.getProperty("user.home")));
    }

    static Path resolveOutputDirectory(Path home) {
        String configured = System.getProperty(OUTPUT_DIRECTORY_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path nativeDefault = home.resolve("Documents/Sports Interactive/Football Manager 26/shortlists")
                .toAbsolutePath().normalize();
        if (Files.isDirectory(nativeDefault)) {
            return nativeDefault;
        }
        // The library holding FM26 wins over stale shortlists folders in other libraries.
        java.util.Optional<Path> gameLib = SteamLibraries.libraryWithApp(home, SteamLibraries.FM26_APP_ID);
        if (gameLib.isPresent()) {
            return gameLib.get()
                    .resolve("steamapps/compatdata/" + SteamLibraries.FM26_APP_ID
                            + "/pfx/drive_c/users/steamuser/Documents/Sports Interactive/Football Manager 26/shortlists")
                    .toAbsolutePath().normalize();
        }
        List<Path> candidates = new ArrayList<>();
        for (Path userDir : SteamLibraries.fmProtonUserDirectories(home)) {
            candidates.add(userDir.resolve("Documents/Sports Interactive/Football Manager 26/shortlists"));
            candidates.add(userDir.resolve("AppData/Local/Sports Interactive/Football Manager 26/cloud/shortlists"));
        }
        return candidates.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .filter(Files::isDirectory)
                .findFirst()
                .orElse(nativeDefault);
    }

    public record CreatedShortlist(
            String name,
            Path path,
            int fileSize,
            List<String> players,
            List<Long> playerUniqueIds,
            String createdAt) {
        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
            result.put("file_path", path.toString());
            result.put("file_size", fileSize);
            result.put("player_count", players.size());
            result.put("players", players);
            result.put("player_unique_ids", playerUniqueIds);
            result.put("next_step", "In FM26, open Scouting > Shortlists and import this .fmf file.");
            return result;
        }
    }
}
