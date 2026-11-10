package com.github.fmaiassistent.update;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import java.util.function.*;

public final class UpdateDownloadService {
    public Path download(UpdateSource source, ReleaseAsset asset, Path directory, BooleanSupplier cancelled, LongConsumer progress) throws IOException {
        Path part = directory.resolve(asset.file() + ".part"), complete = directory.resolve(asset.file());
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            long count = 0, deadline = System.nanoTime() + java.time.Duration.ofMinutes(30).toNanos();
            try (var input = source.openAsset(asset); var output = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[65536];
                for (int read; (read = input.read(buffer)) != -1;) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("Update download cancelled");
                    count += read;
                    if (count > asset.sizeBytes() || System.nanoTime() > deadline) throw new IOException("Update download exceeds size or time limit");
                    hash.update(buffer, 0, read); output.write(buffer, 0, read); progress.accept(count);
                }
            }
            if (count != asset.sizeBytes() || !HexFormat.of().formatHex(hash.digest()).equals(asset.sha256())) throw new IOException("Update checksum or size mismatch");
            return Files.move(part, complete, StandardCopyOption.ATOMIC_MOVE);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        finally { Files.deleteIfExists(part); }
    }
}
