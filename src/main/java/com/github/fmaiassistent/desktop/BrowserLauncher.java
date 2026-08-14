package com.github.fmaiassistent.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.awt.AWTError;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

final class BrowserLauncher {
    private static final Logger log = LoggerFactory.getLogger(BrowserLauncher.class);

    private final UriOpener opener;
    private final AtomicBoolean startupOpenAttempted = new AtomicBoolean();

    BrowserLauncher() {
        this(BrowserLauncher::browseWithDesktop);
    }

    BrowserLauncher(UriOpener opener) {
        this.opener = opener;
    }

    void openAtStartup(URI uri) {
        if (startupOpenAttempted.compareAndSet(false, true)) {
            open(uri);
        }
    }

    void open(URI uri) {
        try {
            opener.open(uri);
            log.info("Opened desktop application in the default browser: {}", uri);
        } catch (Exception | AWTError exception) {
            log.warn("Could not open the default browser. Open {} manually: {}",
                    uri, message(exception));
        }
    }

    private static void browseWithDesktop(URI uri) throws Exception {
        if (!Desktop.isDesktopSupported()) {
            throw new UnsupportedOperationException("Java Desktop integration is unavailable");
        }
        Desktop desktop = Desktop.getDesktop();
        if (!desktop.isSupported(Desktop.Action.BROWSE)) {
            throw new UnsupportedOperationException("The BROWSE desktop action is unavailable");
        }
        desktop.browse(uri);
    }

    private static String message(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    interface UriOpener {
        void open(URI uri) throws Exception;
    }
}
