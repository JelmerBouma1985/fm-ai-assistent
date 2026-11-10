package com.github.fmaiassistent.update.helper;

import java.nio.channels.FileChannel;
import java.nio.file.*;

public final class UpdaterMain {
    private UpdaterMain() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !(args[0].equals("install") || args[0].equals("recover"))) throw new IllegalArgumentException("Usage: install|recover <transaction-directory> <user-home>");
        var transaction = new InstallationTransaction(Path.of(args[1]), Path.of(args[2]), 120000);
        Path install = Path.of(transaction.properties().getProperty("install"));
        Path lockPath = install.resolveSibling(install.getFileName() + ".updater.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IllegalArgumentException("Linked updater lock");
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("Another updater owns this installation");
            try {
                if (args[0].equals("recover")) transaction.recover(); else transaction.execute();
            } catch (Exception error) {
                Files.writeString(Path.of(args[1]).resolve("error.txt"), error.getClass().getSimpleName() + ": " + error.getMessage());
                throw error;
            }
        }
    }
}
