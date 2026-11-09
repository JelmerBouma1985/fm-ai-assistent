package com.github.fmaiassistent.update;

import com.github.fmaiassistent.update.helper.ImageFiles;
import java.nio.file.*;
import java.util.Properties;

/** Real child process for the helper integration tests, without closing the test runner. */
public final class UpdaterProcessFixture {
    public static void main(String[] args) throws Exception {
        if (args[1].equals("healthy")) {
            Path directory = Path.of(args[0]);
            Properties tx = ImageFiles.properties(directory.resolve("transaction.properties"));
            Properties ack = new Properties(); ack.setProperty("id", tx.getProperty("id")); ack.setProperty("version", tx.getProperty("newVersion"));
            ImageFiles.store(directory.resolve("healthy.properties"), ack);
        }
        if (!args[1].equals("fail")) Thread.sleep(30000);
    }
}
