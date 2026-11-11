package com.github.fmaiassistent.update;

import com.github.fmaiassistent.update.helper.ImageFiles;
import java.nio.file.*;
import java.util.Properties;

/** Real child process for the helper integration tests, without closing the test runner. */
public final class UpdaterProcessFixture {
    public static void main(String[] args) throws Exception {
        if (args[1].equals("shutdown")) {
            // Simulate Spring/AWT stopping their last non-daemon threads while
            // the daemon updater worker hands off a slow database shutdown.
            Thread worker = Thread.ofPlatform().daemon().start(() ->
                    UpdateCoordinator.startShutdown(directory -> {
                        try {
                            Thread.sleep(500);
                            Files.createFile(directory.resolve("clean-shutdown"));
                        } catch (Exception failure) {
                            throw new RuntimeException(failure);
                        }
                    }, Path.of(args[0])));
            worker.join();
            return;
        }
        if (args[1].equals("server")) {
            Path directory = Path.of(args[0]);
            Properties tx = ImageFiles.properties(directory.resolve("transaction.properties"));
            try (var socket = new java.net.ServerSocket()) {
                socket.bind(new java.net.InetSocketAddress(tx.getProperty("serverAddress"),
                        Integer.parseInt(tx.getProperty("serverPort"))));
                Files.createFile(directory.resolve("server-listening"));
                while (!Files.exists(directory.resolve("exit-server"))) Thread.sleep(20);
            }
            return;
        }
        if (args[1].equals("install")) {
            Path directory = Path.of(args[0]);
            new com.github.fmaiassistent.update.helper.InstallationTransaction(
                    directory, directory.getParent().getParent().getParent(), 10000).execute();
            return;
        }
        if (args[1].equals("healthy")) {
            Path directory = Path.of(args[0]);
            Properties tx = ImageFiles.properties(directory.resolve("transaction.properties"));
            Properties ack = new Properties(); ack.setProperty("id", tx.getProperty("id")); ack.setProperty("version", tx.getProperty("newVersion"));
            ImageFiles.store(directory.resolve("healthy.properties"), ack);
        }
        if (!args[1].equals("fail")) Thread.sleep(30000);
    }
}
