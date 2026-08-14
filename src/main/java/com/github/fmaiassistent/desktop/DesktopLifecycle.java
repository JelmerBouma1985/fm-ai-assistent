package com.github.fmaiassistent.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

final class DesktopLifecycle {
    private static final Logger log = LoggerFactory.getLogger(DesktopLifecycle.class);

    private final ConfigurableApplicationContext context;
    private final BrowserLauncher browser;
    private final DesktopWindow window;
    private final IntConsumer exitJvm;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    DesktopLifecycle(
            ConfigurableApplicationContext context,
            BrowserLauncher browser,
            DesktopWindow window,
            IntConsumer exitJvm) {
        this.context = context;
        this.browser = browser;
        this.window = window;
        this.exitJvm = exitJvm;
    }

    void start(URI applicationUri) {
        window.applicationReady(() -> browser.open(applicationUri), this::exit);
        browser.openAtStartup(applicationUri);
    }

    void exit() {
        if (shutdown()) {
            exitJvm.accept(0);
        }
    }

    boolean shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return false;
        }
        log.info("Desktop application shutting down");
        window.close();
        try {
            if (context.isActive()) {
                context.close();
            }
            log.info("Spring application context closed");
        } catch (RuntimeException exception) {
            log.error("Spring application context reported an error while closing", exception);
        }
        return true;
    }
}
